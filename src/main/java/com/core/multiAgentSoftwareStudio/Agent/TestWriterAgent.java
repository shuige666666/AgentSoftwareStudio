package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Model.Generation.PrdDocument;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Generation.TestClassesResult;
import com.core.multiAgentSoftwareStudio.Config.TestGenerationConfig;
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
               The contract does not authorize inventing Java method names. Before calling a service, controller,
               DTO, model, or repository method, verify that the exact method exists in EXISTING SOURCE CODE.
               Test observable contract behavior through existing public APIs; never assume methods such as
               `castVote`, `getResults`, getters, constructors, or builders merely from an acceptance description.
               In mocked controller tests, stub every lookup that the exercised controller path invokes before
               its write operation, including `findById` or `get...ById` calls used by update flows.
               For entities using `@GeneratedValue`, never call `setId` or assume IDs such as 1 in integration tests.
               Clear repository state between tests, capture the entity returned by `save`, and build requests from
               its actual generated ID. A test must remain correct regardless of execution order or database sequence.
            10. If the project contains frontend HTML/JS files, generate a lightweight frontend contract test.
                - The test can read files from `src/main/resources/static` or `src/main/resources/templates`.
                - It should assert that JavaScript DOM ids referenced by `getElementById` or `querySelector('#id')` exist in the HTML.
                - It should assert that frontend fetch/axios paths match the backend endpoints from PROJECT CONTRACT.
                - Generated Java assertions must use syntactically valid string literals; escape every quote embedded
                  in an expected HTML/JavaScript fragment and never emit half-open `contains("...")` expressions.
                - For a dynamic Thymeleaf form action, assert the actual conditional expression or both concrete
                  branches without contradictory whole-file checks such as requiring and forbidding `/edit` at once.
                - Keep assertions scoped to the resource that owns the interaction. Form field ids belong in the
                  create/edit form template; never require them in a list or detail template merely because the
                  project contains that form elsewhere.
                - A contracted DELETE/PUT/PATCH interaction may be implemented by JavaScript fetch/axios. If the
                  existing resource issues the correct dynamic request (for example `fetch('/urls/' + shortCode,
                  { method: 'DELETE' })`), assert that implementation; do not require a contradictory th:action or
                  HTML form method that cannot natively represent the HTTP verb.
                - Do not add Playwright, Selenium, jsdom, or other heavyweight dependencies unless they already exist.
            11. In MockMvc tests, an exception without an existing `@ExceptionHandler`, `@ControllerAdvice`,
                `@ResponseStatus`, or explicit `ResponseEntity` mapping is rethrown as `ServletException`.
                Never assert `status().isInternalServerError()` for such an unhandled exception. Only assert a mapped
                HTTP error status that the existing production code explicitly implements. Otherwise test the service
                exception at the service boundary and keep the controller test focused on mapped HTTP behavior.
            12. Build state-transition fixtures from the production algorithm's next state, not its current state.
                For movement/eating tests, place food at the next head coordinate in the chosen direction. For enum or
                protocol errors, assert the behavior actually implemented by the deserializer and handler; do not call
                syntactically valid JSON an unknown enum message unless the enum defines such a fallback.
            13. Mockito `reset(mock)` removes both interactions and stubbing. Prefer `clearInvocations(mock)` when a
                test only needs to discard earlier calls. If reset is necessary, restore every required stub such as
                `session.isOpen()` before exercising the next action.
            14. Test redirects as real HTTP behavior: expect the contract's 3xx status and Location header, and expect
                the declared not-found status for an unknown resource. Never assert HTTP 500 for a successful redirect.
            15. Parse response JSON with ObjectMapper, JsonPath, or Spring test JSON assertions. Never extract identifiers
                with manual substring offsets. Do not verify an exact retry count for random collision handling unless
                the random/code generator is explicitly injectable or otherwise controllable by the test fixture.
            16. For an initial vertical slice, prefer a compact acceptance suite over exhaustive unit-test enumeration.
                Cover each acceptance criterion's main path once and add only high-risk boundaries that can change the
                observable result. Do not create many near-duplicate getter, constructor, or permutation tests.
            17. For a test-repair request, modify only test classes explicitly named by TEST FAILURE EVIDENCE. Return
                complete corrected versions of those files only; never regenerate unrelated green test classes.

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
     * 针对一个垂直切片生成验收测试，避免每次都重新设计整个项目的测试集合。
     */
    public TestClassesResult writeSliceTests(
            PrdDocument prd,
            ProjectStructure structure,
            ProjectContract sliceContract,
            String existingCode,
            String sliceName,
            java.util.List<String> acceptanceCriteria) {
        String sliceEvidence = """
                VERTICAL SLICE: %s
                ACCEPTANCE CRITERIA:
                %s

                Generate tests for this slice only. Preserve compatibility with already implemented slices.
                Return at least one focused test for the current acceptance criteria. A shared Spring context
                test may be returned when required by the project profile. Use a slice-specific test class name;
                never overwrite or reuse an existing accepted test file.
                Aim for %d-%d total @Test methods across this initial slice suite; fewer are valid when the slice has
                few observable behaviors. For each acceptance criterion, create one primary test and at most %d
                high-risk boundary tests. Do not spend the initial slice budget on exhaustive permutations.
                """.formatted(
                sliceName,
                acceptanceCriteria,
                TestGenerationConfig.RECOMMENDED_MIN_TEST_METHODS_PER_SLICE,
                TestGenerationConfig.RECOMMENDED_MAX_TEST_METHODS_PER_SLICE,
                TestGenerationConfig.MAX_RISK_BOUNDARY_TESTS_PER_CRITERION);
        return rewriteTests(prd, structure, sliceContract, existingCode, sliceEvidence);
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

                When failure evidence names one or more test classes, return only those complete corrected test files.
                Preserve every unrelated test unchanged by omitting it from the response.
                """.formatted(
                prd, structure, contract, existingCode,
                testFailureEvidence == null ? "No prior test failure." : testFailureEvidence);
        return askJson(SYSTEM_PROMPT, userPrompt, TestClassesResult.class);
    }
}
