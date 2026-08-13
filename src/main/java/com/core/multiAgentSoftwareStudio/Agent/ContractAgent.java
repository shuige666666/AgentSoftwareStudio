package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Model.Generation.PrdDocument;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * 角色 B-2 ：契约架构师
 * 生成项目接口文档和前后端交互契约
 */
public class ContractAgent extends AbstractJsonAgent {

    private static final String SYSTEM_PROMPT = """
            You are a senior API and UI contract designer.
            Your task is to create a structured project contract after the architect has produced the skeleton.

            Responsibilities:
            1. Define backend endpoints with method, path, request DTO, response DTO, implementing controller, and purpose.
            2. Define MVC views when controllers return templates, including view name and template path.
            3. Define frontend calls from html/js files to backend endpoints.
            4. Add missing blueprint files required by the contract, such as DTOs, templates, static JS/CSS, or controllers.

            Rules:
            1. Production classes, DTOs, entities, repositories, services, controllers, and config files MUST be under src/main/java.
            2. Test files MUST NOT be added here.
            3. DTO file paths MUST use the project's root package and a dto package.
            4. Template files MUST be under src/main/resources/templates.
            5. Static frontend files MUST be under src/main/resources/static.
            6. Every frontend call path MUST exactly match one backend endpoint path.
            7. Every MVC view returned by a controller MUST have a matching template file.
            8. additionalFiles MUST only contain files that are missing from the architecture skeleton but required by this contract.
            9. frontendCalls.sourceFile MUST be the exact html/js file that issues the request through a link, form,
               fetch, or axios call. For server-rendered MVC, include every user-reachable create, detail, edit,
               submit, and delete interaction; never assign a frontend call to a Controller source file.

            JSON FORMAT RULES:
            1. Return a valid JSON object matching ProjectContract.
            2. Do NOT wrap JSON in markdown fences.
            3. Use arrays named endpoints, views, frontendCalls, and additionalFiles.
            4. For additionalFiles, each item MUST include fileName, filePath, layer, batchName, functionalityDescription, keyMethods, and dependsOn.
            """;

    public ContractAgent(ChatLanguageModel model,
            LangGraphPromptExecutor promptExecutor,
            ObjectMapper objectMapper) {
        super(model, promptExecutor, objectMapper);
    }

    /**
     * 根据 PRD 和项目骨架生成结构化交互契约
     */
    public ProjectContract designContract(PrdDocument prd, ProjectStructure structure) {
        String userPrompt = """
                === PRD ===
                %s

                === ARCHITECTURE SKELETON ===
                %s
                """.formatted(prd, structure);
        return askJson(SYSTEM_PROMPT, userPrompt, ProjectContract.class);
    }
}
