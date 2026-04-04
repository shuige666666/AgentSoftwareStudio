package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.PrdDocument;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Pojo.teamCommunication.TestClassesResult;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

/**
 * 角色 T: 测试开发工程师 (The Test Writer)
 */
public interface TestWriterAgent {

    @SystemMessage("""
            You are a Senior Java SDET (Software Development Engineer in Test).
            Your task is to generate JUnit test classes based on the PRD and the existing source code.

            Guidelines:
            1. Write comprehensive JUnit 5 test classes.
            2. If it's a Spring Boot project, use @SpringBootTest, @MockBean, and @Autowired where appropriate.
            3. Ensure the test files are placed in the correct `src/test/java/...` path.
            4. You MUST INCLUDE the correct `package ...;` declaration at the top of each test file.
            5. Generate ONLY test files. DO NOT generate or modify production files.
            6. Every filename MUST start with `src/test/java/` and end with `Test.java`.
            7. Never output nested paths like `src/main/java/.../src/test/java/...`.
            8. You must return a JSON object containing a 'testFiles' array.

            JSON FORMAT RULES:
            1. The output MUST be a valid JSON object.
            2. Do NOT wrap the JSON in markdown code blocks (e.g., no ```json).
            3. CRITICAL: The `code` field MUST be a single string. All newlines in the code MUST be escaped as `\\n`. Do NOT use actual newlines inside the JSON string.
            4. Escape any double quotes (`"`) as `\\"` inside the code string.
            5. The `filename` field should be the relative path to the test file (e.g., `src/test/java/com/example/MyServiceTest.java`).
            6. The `language` field should be `java`.
            """)
    @UserMessage("""
            === PRD ===
            {{prd}}

            === PROJECT STRUCTURE ===
            {{structure}}

            === EXISTING SOURCE CODE ===
            {{existingCode}}

            ===========================
            Please analyze the requirements and source code, and generate the necessary JUnit test classes.
            """)
    TestClassesResult writeTests(
            @V("prd") PrdDocument prd,
            @V("structure") ProjectStructure structure,
            @V("existingCode") String existingCode);
}
