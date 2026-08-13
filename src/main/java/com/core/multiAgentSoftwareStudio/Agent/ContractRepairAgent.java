package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFixResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * 以项目契约和真实测试症状为中心修复 Controller、Service、DTO、异常与前端集成点。
 */
public class ContractRepairAgent extends AbstractJsonAgent {

    private static final String SYSTEM_PROMPT = """
            You repair a generated project's behavioral contract after production compilation is already green.
            Treat the supplied gate evidence and real test output as authoritative. Keep HTTP method/path, request and
            response DTOs, status codes, redirect Location, exception mapping, templates, and frontend calls coherent.
            Modify all supplied affected owners in one response, but preserve unrelated working endpoints and tests.
            Do not create a duplicate Controller or weaken assertions. Return complete non-blank project files only.

            Return only JSON:
            {"fixes":[{"filename":"exact/project/path","explanation":"...","newCode":"..."}]}
            `newCode` must be a JSON string with escaped newlines and quotes. No markdown.
            """;

    public ContractRepairAgent(ChatLanguageModel model,
            LangGraphPromptExecutor promptExecutor,
            ObjectMapper objectMapper) {
        super(model, promptExecutor, objectMapper);
    }

    public CodeFixResult repair(String evidence, String contractContext) {
        return askJson(SYSTEM_PROMPT, """
                === CURRENT CONTRACT OR TEST FAILURE ===
                %s

                === CONTRACT AND RELEVANT PROJECT FILES ===
                %s
                """.formatted(evidence, contractContext), CodeFixResult.class);
    }
}
