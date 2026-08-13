package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFixResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * 处理无法由单文件 Developer 或 TestWriter 闭环的跨文件修复，真实工具验证由平台负责。
 */
public class DebuggerAgent extends AbstractJsonAgent {

    private static final String SYSTEM_PROMPT = """
            You are the fallback repair engineer for a generated Java project.
            The platform will compile and test every candidate after you return it. Treat the latest real tool output
            as authoritative and repair the earliest remaining root cause, not old symptoms.

            CORE RULES:
            1. Use only types, methods, constructors, endpoints, and files shown in the supplied project context.
            2. Return the smallest cross-file-consistent change that resolves the current failure.
            3. Preserve working public behavior. Never delete required functionality or weaken tests to obtain green output.
            4. Modify only supplied project-relative files. Do not invent speculative files, framework APIs, or dependencies.
            5. A Java fix must contain the complete non-blank file, including its correct package and imports.
            6. Keep callers and owners consistent: when changing a DTO, method, constructor, endpoint, or exception,
               update every supplied affected file in the same response.
            7. For implementation failures, do not modify tests. For test-owned failures, do not change production behavior.
            8. If the evidence is insufficient for a safe concrete change, return an empty fixes array.

            OUTPUT:
            Return only valid JSON with this shape:
            {"fixes":[{"filename":"src/main/java/.../ActualFile.java","explanation":"...","newCode":"..."}]}
            Every filename must be copied exactly from the supplied context. `newCode` is one JSON string with escaped
            newlines and quotes. Do not use markdown or placeholder filenames.
            """;

    public DebuggerAgent(ChatLanguageModel model,
            LangGraphPromptExecutor promptExecutor,
            ObjectMapper objectMapper) {
        super(model, promptExecutor, objectMapper);
    }

    /**
     * 根据结构化失败类型只追加本轮需要的专项约束，避免永久携带所有历史案例规则。
     */
    public CodeFixResult analyzeAndFix(String errorType, String errorLog, String currentCodeContext) {
        String userPrompt = """
                === REPAIR OWNERSHIP ===
                %s

                === TARGET-SPECIFIC POLICY ===
                %s

                === LATEST REAL TOOL EVIDENCE ===
                %s

                === RELEVANT PROJECT CONTEXT ===
                %s
                """.formatted(errorType, policyFor(errorType), errorLog, currentCodeContext);
        return askJson(SYSTEM_PROMPT, userPrompt, CodeFixResult.class);
    }

    private String policyFor(String errorType) {
        String type = errorType == null ? "UNKNOWN" : errorType;
        return switch (type) {
            case "MAIN_COMPILE", "IMPLEMENTATION", "SPRING_CONTEXT" -> """
                    Repair production code only. Copy exact signatures from the supplied definitions and callers.
                    For missing members or wiring, update the owning type and all supplied affected callers together.
                    Do not replace declared DTOs with Map/Object or add speculative configuration.
                    """;
            case "CONTRACT" -> """
                    Repair the supplied endpoint, DTO, exception, template, or frontend contract as one coherent change.
                    Keep HTTP method/path, request/response shape, status, redirect Location, and error mapping aligned.
                    Do not create a second Controller for an endpoint already owned by an existing Controller.
                    """;
            case "TEST_COMPILE", "TEST_DISCOVERY", "TEST_CODE", "TEST_ASSERTION" -> """
                    Modify tests only when the evidence proves the test is malformed or makes an invalid infrastructure
                    assumption. Otherwise repair the supplied production owner and preserve the behavioral assertion.
                    """;
            default -> "Trace the earliest failure across the supplied files and make one minimal coherent repair.";
        };
    }
}
