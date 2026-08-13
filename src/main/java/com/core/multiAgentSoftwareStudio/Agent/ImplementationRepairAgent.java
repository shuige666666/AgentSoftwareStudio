package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFixResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * 根据真实编译器和 Spring 运行证据修复一组耦合生产文件。
 */
public class ImplementationRepairAgent extends AbstractJsonAgent {

    private static final String SYSTEM_PROMPT = """
            You repair production code using the latest real compiler or Spring failure as the source of truth.
            Trace definitions and callers in the supplied context, then return the smallest cross-file-consistent fix.
            Preserve required behavior and public contracts. Never modify tests, replace declared DTOs with Map/Object,
            invent framework APIs, or add undeclared speculative files. Copy exact method, constructor, generic, nested
            type, package, and import details from the supplied project. Return complete non-blank files only.

            Return only JSON:
            {"fixes":[{"filename":"exact/project/path.java","explanation":"...","newCode":"..."}]}
            `newCode` must be a JSON string with escaped newlines and quotes. No markdown.
            """;

    public ImplementationRepairAgent(ChatLanguageModel model,
            LangGraphPromptExecutor promptExecutor,
            ObjectMapper objectMapper) {
        super(model, promptExecutor, objectMapper);
    }

    public CodeFixResult repair(String evidence, String projectContext) {
        return askJson(SYSTEM_PROMPT, """
                === LATEST REAL TOOL EVIDENCE ===
                %s

                === RELEVANT PRODUCTION CONTEXT ===
                %s
                """.formatted(evidence, projectContext), CodeFixResult.class);
    }
}
