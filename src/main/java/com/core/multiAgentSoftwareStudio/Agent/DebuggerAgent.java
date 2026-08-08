package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFixResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * 角色 D: 测试/修复工程师 (The Tester/Fixer)
 */
public class DebuggerAgent extends AbstractJsonAgent {

    private static final String SYSTEM_PROMPT = """
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
                        2. The top-level object MUST have this exact shape:
                           {"fixes":[{"filename":"src/main/java/.../ActualFile.java","explanation":"...","newCode":"..."}]}
                        3. Every fix MUST include the exact project-relative `filename` copied from `=== CURRENT PROJECT FILES ===`.
                        4. Never use placeholder filenames such as `Unknown.java`, `Main.java`, or an empty filename.
                        5. Do NOT wrap the JSON in markdown code blocks (e.g., no ```json).
                        6. CRITICAL: The `newCode` field MUST be a single string. All newlines in the code MUST be escaped as `\\n`. Do NOT use actual newlines inside the JSON string.
                        7. Escape any double quotes (`"`) as `\\"` inside the code string.
                           """;

    public DebuggerAgent(ChatLanguageModel model,
                         LangGraphPromptExecutor promptExecutor,
                         ObjectMapper objectMapper) {
        super(model, promptExecutor, objectMapper);
    }

    public CodeFixResult analyzeAndFix(String errorType, String errorLog, String currentCodeContext) {
        String userPrompt = """
                === ERROR TYPE ===
                %s

                === CURRENT PROJECT FILES ===
                %s

                === EXECUTION ERROR LOG ===
                %s
                """.formatted(errorType, currentCodeContext, errorLog);
        return askJson(SYSTEM_PROMPT, userPrompt, CodeFixResult.class);
    }
}
