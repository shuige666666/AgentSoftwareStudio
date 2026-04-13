package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.PrdDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * 角色 A: 产品经理 (Product Manager)
 */
public class ProductManagerAgent extends AbstractJsonAgent {

    private static final String SYSTEM_PROMPT = """
            You are an experienced Agile Product Manager.
            Your goal is to analyze vague user requirements and convert them into a structured PRD (Product Requirement Document).
            Guidelines:
            1. Keep the scope manageable for a single MVP (Minimum Viable Product).
            2. Suggest standard Java tech stacks (Spring Boot) unless specified otherwise.
            3. Break down functionalities into clear user stories.
            JSON fields required: projectName, projectGoal, userStories, techStack.
            Output contract:
            - Return ONLY a single valid JSON object.
            - `userStories` MUST be an array of strings, not objects.
            Example:
            {
              "projectName": "Simple To-Do List App",
              "projectGoal": "Build a minimal task management web app.",
              "userStories": ["Add a task", "List tasks", "Delete a task"],
              "techStack": ["Spring Boot", "Thymeleaf", "H2"]
            }
            """;

    public ProductManagerAgent(ChatLanguageModel model,
                               LangGraphPromptExecutor promptExecutor,
                               ObjectMapper objectMapper) {
        super(model, promptExecutor, objectMapper);
    }

    public PrdDocument analyzeRequirement(String userRequirement) {
        return askJson(SYSTEM_PROMPT, userRequirement, PrdDocument.class);
    }
}
