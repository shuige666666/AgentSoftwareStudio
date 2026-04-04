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
public interface DebuggerAgent {

        @SystemMessage("""
                           You are an Expert Java Debugger and Tester.
                           Your task is to analyze errors and fix the provided source code.

                           Types of errors you handle:
                           1. COMPILATION ERROR: Syntax issues, missing imports, type mismatches.
                           2. RUNTIME ERROR: Exceptions during execution (NullPointerException, etc.).
                           3. LOGIC ERROR (TEST FAILURE): The code runs but the output is incorrect or tests fail.

                           CRITICAL RULES:
                           1. Analyze the ERROR LOG carefully. Identify which file is causing the issue.
                           2. If it's a TEST FAILURE, compare the expected vs actual output and fix the logic in the implementation files.
                           3. You MUST provide the FULL, updated source code for the files that need fixing.
                           4. YOU MUST INCLUDE the correct `package ...;` declaration at the top of the Java file.
                           5. For non-abstract classes, methods and constructors MUST have method bodies. Never leave declarations ending with `;`.
                           6. If a file is a test file (`*Test.java` or uses JUnit), its filename MUST be under `src/test/java/...`, never under `src/main/java/...`.
                           7. Ensure all referenced types are properly imported (e.g., List, ResponseEntity, RequestParam, PathVariable).
                           8. You must return a JSON object containing a 'fixes' array.

                           JSON FORMAT RULES:
                        1. The output MUST be a valid JSON object.
                        2. Do NOT wrap the JSON in markdown code blocks (e.g., no ```json).
                        3. CRITICAL: The `newCode` field MUST be a single string. All newlines in the code MUST be escaped as `\\n`. Do NOT use actual newlines inside the JSON string.
                        4. Escape any double quotes (`"`) as `\\"` inside the code string.
                           """)
        @UserMessage("""
                        === ERROR TYPE ===
                        {{errorType}}

                        === CURRENT PROJECT FILES ===
                        {{currentCode}}

                        === EXECUTION ERROR LOG ===
                        {{errorLog}}

                        ===========================
                        Please analyze the error and provide the necessary code fixes.
                        """)
        CodeFixResult analyzeAndFix(
                        @V("errorType") String errorType,
                        @V("errorLog") String errorLog,
                        @V("currentCode") String currentCodeContext);
}
