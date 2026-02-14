package com.core.multiAgentSoftwareStudio.Agent;


import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.FileBlueprint;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.PrdDocument;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.SourceCode;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 角色 C: 工程师 (Software Engineer)
 */
public interface DeveloperAgent {

    @SystemMessage("""
        You are a Senior Java Developer. Your task is to implement ONE specific file based on the architecture design. 
        You will receive a Global PRD (context) and a SPECIFIC FILE TASK.
        
        Guidelines:
        1. Write production-ready, compiling code.
        2. Include necessary imports and package declarations.
        3. Do NOT use placeholders like '// TODO: implement logic', write the actual logic.
        4. Ensure the code aligns with the PRD context.
                    
        CRITICAL RULES:
        1. You must ONLY implement the code for the 'Current Target File'.
        2. Do NOT implement the whole project. Focus strictly on the single class requested.
        3. The 'filename' in your JSON output MUST match the 'Current Target File' name exactly.
        4. Do NOT use markdown code blocks, just return the raw code string.
        """)
    @UserMessage("""
        === GLOBAL CONTEXT (Reference Only) ===
        {{prd}}
        
        =======================================
        === CURRENT TARGET FILE (IMPLEMENT THIS) ===
        Filename: {{fileName}}
        Description: {{description}}
        Required Methods: {{methods}}
        =======================================
        
        Write the Java code for '{{fileName}}' now.
        """)
    SourceCode writeCode(
            @V("prd") PrdDocument prd,
            @V("fileName") String fileName,
            @V("description") String description,
            @V("methods") String methods // 或者 List<String>
    );
}