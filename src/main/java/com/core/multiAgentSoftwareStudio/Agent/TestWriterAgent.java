package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Model.Generation.PrdDocument;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.TestClassesResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * 角色 T: 测试开发工程师 (The Test Writer)
 */
public class TestWriterAgent extends AbstractJsonAgent {

    private static final String SYSTEM_PROMPT = """
            You are a Senior Java SDET (Software Development Engineer in Test).
            Your task is to generate JUnit test classes based on the PRD and the existing source code.

            Guidelines:
            1. Write comprehensive JUnit 5 test classes.
            2. If it's a Spring Boot project, always include at least one @SpringBootTest context test.
               Use `org.springframework.boot.test.mock.mockito.MockBean` for Spring Boot 3.2 projects;
               never use `MockitoBean` or `org.springframework.boot.test.mock.bean.MockBean`.
            3. Ensure the test files are placed in the correct `src/test/java/...` path.
            4. You MUST INCLUDE the correct `package ...;` declaration at the top of each test file.
            5. Generate ONLY test files. DO NOT generate or modify production files.
            6. Every filename MUST start with `src/test/java/` and end with `Test.java`.
            7. Never output nested paths like `src/main/java/.../src/test/java/...`.
            8. You must return a JSON object containing a 'testFiles' array.
            9. If PROJECT CONTRACT defines endpoints or DTOs, tests MUST use those exact paths and payload names.
            10. If the project contains frontend HTML/JS files, generate a lightweight frontend contract test.
                - The test can read files from `src/main/resources/static` or `src/main/resources/templates`.
                - It should assert that JavaScript DOM ids referenced by `getElementById` or `querySelector('#id')` exist in the HTML.
                - It should assert that frontend fetch/axios paths match the backend endpoints from PROJECT CONTRACT.
                - Generated Java assertions must use syntactically valid string literals; escape every quote embedded
                  in an expected HTML/JavaScript fragment and never emit half-open `contains("...")` expressions.
                - Do not add Playwright, Selenium, jsdom, or other heavyweight dependencies unless they already exist.

            JSON FORMAT RULES:
            1. The output MUST be a valid JSON object.
            2. Do NOT wrap the JSON in markdown code blocks (e.g., no ```json).
            3. CRITICAL: The `code` field MUST be a single string. All newlines in the code MUST be escaped as `\\n`. Do NOT use actual newlines inside the JSON string.
            4. Escape any double quotes (`"`) as `\\"` inside the code string.
            5. The `filename` field should be the relative path to the test file (e.g., `src/test/java/com/example/MyServiceTest.java`).
            6. The `language` field should be `java`.
            """;

    public TestWriterAgent(ChatLanguageModel model,
                           LangGraphPromptExecutor promptExecutor,
                           ObjectMapper objectMapper) {
        super(model, promptExecutor, objectMapper);
    }

    /**
     * 根据项目源码和接口契约生成测试文件，确保测试使用真实接口路径和 DTO 名称
     */
    public TestClassesResult writeTests(PrdDocument prd, ProjectStructure structure, ProjectContract contract,
            String existingCode) {
        return rewriteTests(prd, structure, contract, existingCode, null);
    }

    /**
     * 针对测试所有权失败重新生成测试；错误日志只用于修正测试代码，不允许改生产源码。
     */
    public TestClassesResult rewriteTests(
            PrdDocument prd,
            ProjectStructure structure,
            ProjectContract contract,
            String existingCode,
            String testFailureEvidence) {
        String userPrompt = """
                === PRD ===
                %s

                === PROJECT STRUCTURE ===
                %s

                === PROJECT CONTRACT ===
                %s

                === EXISTING SOURCE CODE ===
                %s

                === TEST FAILURE EVIDENCE (Fix test-owned problems only) ===
                %s
                """.formatted(
                prd, structure, contract, existingCode,
                testFailureEvidence == null ? "No prior test failure." : testFailureEvidence);
        return askJson(SYSTEM_PROMPT, userPrompt, TestClassesResult.class);
    }
}
