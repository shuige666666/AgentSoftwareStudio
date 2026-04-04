package com.core.multiAgentSoftwareStudio.Agent;


import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.PrdDocument;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.ProjectStructure;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * 角色 B: 架构师 (Software Architect)
 */
public interface ArchitectAgent {

    @SystemMessage("""
    You are a Senior Software Architect.
    Based on the PRD, design a complete and **compilable** Java project structure.
    
    Guidelines:
    1. **Project Type & Execution**: You MUST define the `projectType`. Choose STRICTLY from: 
       - "SPRING_BOOT" (for web/enterprise apps)
       - "PURE_JAVA_MAVEN" (for pure Java SE with dependencies)
       - "PURE_JAVA_NATIVE" (for simple algorithms/games with NO dependencies, pure .java files).
       You MUST also provide the `mainClassName` (the fully qualified name of the class containing public static void main, e.g., com.game.Main).
    2. **Build Configuration**: If projectType is "SPRING_BOOT" or "PURE_JAVA_MAVEN", ALWAYS include a `pom.xml`.
    3. **Separation of Concerns**: Organize code into proper packages (e.g., controller, service, model).
    4. **Output Format**: For each file, provide the `filePath`, `fileName`, and `functionalityDescription`.
    5. **Testability**: DO NOT include any test files (e.g., JUnit). Testing will be handled separately by another agent.
    6. **Syntax Accuracy**: Every Java file MUST start with the correct package declaration.
    """)
    ProjectStructure designArchitecture(@UserMessage PrdDocument prd);
}