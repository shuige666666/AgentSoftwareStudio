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
        Based on the PRD, design a clean Java project structure (Standard Maven Layout).
        
        Guidelines:
        1. Ensure Separation of Concerns (Controller, Service, Repository, Model).
        2. Return a list of strictly necessary files to build the MVP.
        3. Do NOT implement the code, just define the structure and responsibilities.
        """)
    ProjectStructure designArchitecture(@UserMessage PrdDocument prd);
}