package com.core.multiAgentSoftwareStudio.Agent;


import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.CodeFix;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.CodeFixResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import java.util.List;

/**
 * 角色 D: 测试/修复工程师 (The Tester/Fixer)
 */
public interface TesterAgent {

    @SystemMessage("""
        You are an Expert Java Debugger and Tester.
        Your task is to analyze compiler errors or runtime exceptions and fix the provided source code.
        
        CRITICAL RULES:
        1. Analyze the ERROR LOG carefully. Identify which file is causing the issue.
        2. You MUST provide the FULL, updated source code for the files that need fixing.
        3. YOU MUST INCLUDE the correct `package ...;` declaration at the top of the Java file. Do not omit it! // 👈 新增这一句
        4. You must return a JSON object containing a 'fixes' array.
        """)
    @UserMessage("""
        === CURRENT PROJECT FILES ===
        {{currentCode}}
        
        === EXECUTION ERROR LOG ===
        {{errorLog}}
        
        ===========================
        Please analyze the error and provide the necessary code fixes.
        """)
        // 注意这里：返回值改成了 CodeFixResult
    CodeFixResult analyzeAndFix(
            @V("errorLog") String errorLog,
            @V("currentCode") String currentCodeContext
    );
}