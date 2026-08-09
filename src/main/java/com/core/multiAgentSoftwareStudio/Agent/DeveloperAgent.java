package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Model.Generation.PrdDocument;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * 角色 C: 工程师 (Software Engineer)
 */
public class DeveloperAgent extends AbstractJsonAgent {

    private static final String SYSTEM_PROMPT = """
                        You are a Senior Java Developer. Your task is to implement ONE specific file based on the architecture design.
                        You will receive a Global PRD, the Project Structure (context), and a SPECIFIC FILE TASK.

                        Guidelines:
                        1. Write production-ready, compiling code.
                        2. Include necessary imports and package declarations.
                        3. Do NOT use placeholders like '// TODO: implement logic', write the actual logic.
                        4. Ensure the code aligns with the PRD and the overall Project Structure.
                        5. CRITICAL: Pay attention to the interaction between files. For example:
                           - Controller endpoints MUST match what the Frontend expects.
                           - Service methods MUST match what the Controller calls.
                           - Field names in DTOs/Models MUST be consistent across the project.
                        6. If PROJECT CONTRACT defines endpoints, DTOs, MVC views, or frontend calls related to this file, follow it exactly.
                        7. For frontend files, every DOM id referenced by JavaScript (`getElementById`, `querySelector('#id')`) MUST exist in the generated HTML.
                        8. For interactive screens, wire button/event handlers end-to-end so user actions trigger the intended fetch or UI transition.
                        9. If using visibility classes such as `d-none`, always implement both show and hide transitions consistently.
                        10. For a Spring Boot pom.xml, use Java 17, Spring Boot 3.2.4, and spring-boot-starter-test.
                            Never add `spring-websocket-test` or Spring test annotations from newer incompatible releases.

                        CRITICAL RULES:
                        1. You must ONLY implement the code for the 'Current Target File'.
                        2. Do NOT implement the whole project. Focus strictly on the single class requested.
                        3. The 'filename' in your JSON output MUST match the 'Current Target File' path exactly.
                        4. Do NOT use markdown code blocks, just return a valid JSON object.

                        JSON FORMAT RULES:
                        1. The output MUST be a valid JSON object matching the `SourceCode` record.
                        2. The `code` field MUST be a single string. All newlines in the code MUST be escaped as `\\n`.
                        3. Escape any double quotes (`"`) as `\\"` inside the code string.
                        """;

    public DeveloperAgent(ChatLanguageModel model,
                          LangGraphPromptExecutor promptExecutor,
                          ObjectMapper objectMapper) {
        super(model, promptExecutor, objectMapper);
    }

    /**
     * 根据 PRD、项目骨架、接口契约和当前文件蓝图生成单个源码文件
     */
    public SourceCode writeCode(PrdDocument prd,
                                ProjectStructure structure,
                                ProjectContract contract,
                                String existingCode,
                                String fileName,
                                String description,
                                String methods,
                                String batchContext) {
        String userPrompt = """
                === GLOBAL CONTEXT (Reference Only) ===
                %s

                === PROJECT STRUCTURE (Reference Only) ===
                %s

                === PROJECT CONTRACT (Must Follow) ===
                %s

                === CURRENT GENERATION BATCH ===
                %s

                === ALREADY IMPLEMENTED FILES (Reference Only) ===
                %s

                =======================================
                === CURRENT TARGET FILE (IMPLEMENT THIS) ===
                Filename: %s
                Description: %s
                Required Methods: %s
                =======================================
                """.formatted(prd, structure, contract, batchContext, existingCode, fileName, description, methods);
        return askJson(SYSTEM_PROMPT, userPrompt, SourceCode.class);
    }
}
