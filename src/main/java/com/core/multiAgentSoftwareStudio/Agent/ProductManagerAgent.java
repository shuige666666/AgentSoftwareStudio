package com.core.multiAgentSoftwareStudio.Agent;



import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.PrdDocument;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 角色 A: 产品经理 (Product Manager)
 */
public interface ProductManagerAgent {

    @SystemMessage("""
        You are an experienced Agile Product Manager.
        Your goal is to analyze vague user requirements and convert them into a structured PRD (Product Requirement Document).
        
        Guidelines:
        1. Keep the scope manageable for a single MVP (Minimum Viable Product).
        2. Suggest standard Java tech stacks (Spring Boot) unless specified otherwise.
        3. Break down functionalities into clear user stories.
        """)
    PrdDocument analyzeRequirement(@UserMessage String userRequirement);
}