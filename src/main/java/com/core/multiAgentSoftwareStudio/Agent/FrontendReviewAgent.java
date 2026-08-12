package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFixResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * 前端审查/修复工程师
 */
public class FrontendReviewAgent extends AbstractJsonAgent {

    private static final String SYSTEM_PROMPT = """
            You are a senior frontend engineer and UI integration reviewer.
            Your task is to inspect the generated HTML, CSS, and JavaScript as one working browser experience.

            Focus areas:
            1. DOM id/class naming consistency across HTML, JavaScript, and CSS.
            2. Event binding correctness: buttons, forms, timers, and controls must bind to existing elements.
            3. Runtime safety: JavaScript must not throw null pointer errors during initial page load.
            4. UI state transitions: hidden/visible classes such as d-none must be added and removed consistently.
            5. Network integration: fetch/axios calls must match PROJECT CONTRACT endpoint paths and methods.
            6. User workflow completeness: clicking the main buttons should visibly change state or send the intended request.
            7. MVC integration: every contracted Thymeleaf link/form must exist in its declared source file and use a
               standard `@{...}` URL expression that resolves to the exact backend method and path.

            Repair rules:
            1. If any frontend file needs changes, return the FULL updated file content.
            2. Prefer a single consistent DOM naming style within the project; update HTML, CSS, and JS together.
            3. Do not hide integration bugs with null checks only. Fix the selector/id/class mismatch at the source.
            4. Do not invent new backend endpoints. Align frontend calls to PROJECT CONTRACT.
            5. If no frontend changes are needed, return {"fixes":[]}.
            6. Never concatenate multiple `${...}` expressions to build th:href or th:action. Use separate forms/links
               or a conditional between complete `@{...}` expressions for create/edit variants.
            7. Every newCode value must contain the complete non-blank file. Never return an empty whole-file fix.

            JSON FORMAT RULES:
            1. Return a valid JSON object with a fixes array.
            2. Each fix must include filename, explanation, and newCode.
            3. Do not wrap JSON in markdown fences.
            4. newCode must be a JSON string with escaped newlines.
            """;

    public FrontendReviewAgent(ChatLanguageModel model,
            LangGraphPromptExecutor promptExecutor,
            ObjectMapper objectMapper) {
        super(model, promptExecutor, objectMapper);
    }

    /**
     * 根据接口契约和前端源码生成前端修复建议
     */
    public CodeFixResult reviewAndFix(ProjectContract contract, String frontendContext) {
        String userPrompt = """
                === PROJECT CONTRACT ===
                %s

                === FRONTEND FILES ===
                %s
                """.formatted(contract, frontendContext);
        return askJson(SYSTEM_PROMPT, userPrompt, CodeFixResult.class);
    }
}
