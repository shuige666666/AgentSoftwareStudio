package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairTarget;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailureTriageServiceTest {

    private final FailureTriageService service = new FailureTriageService();

    /**
     * 测试编译和测试发现问题归 TestWriter 所有，并继续消耗共享预算。
     */
    @Test
    void routesTestCompileFailureToTests() {
        SoftwareStudioWorkflowData data = data(FailureKind.TEST_COMPILE, "cannot find MockBean");

        var decision = service.decide(data);

        assertEquals(RepairTarget.TESTS, decision.target());
        assertTrue(decision.retryable());
        assertTrue(decision.llmAllowed());
    }

    /**
     * 测试运行期的 Mockito 误用同样归 TestWriter 所有。
     */
    @Test
    void routesTestCodeFailureToTests() {
        SoftwareStudioWorkflowData data = data(FailureKind.TEST_CODE, "InvalidUseOfMatchersException");

        var decision = service.decide(data);

        assertEquals(RepairTarget.TESTS, decision.target());
        assertTrue(decision.retryable());
    }

    /**
     * Jackson 无法构造生产模型时应修复模型所有者，不能通过删除往返序列化测试绕过问题。
     */
    @Test
    void routesJacksonConstructionFailureToImplementation() {
        SoftwareStudioWorkflowData data = data(
                FailureKind.TEST_CODE,
                "InvalidDefinitionException: Cannot construct instance of `com.example.Point` "
                        + "(no Creators, like default constructor, exist) through reference chain");

        var decision = service.decide(data);

        assertEquals(RepairTarget.IMPLEMENTATION, decision.target());
        assertTrue(decision.retryable());
    }

    /**
     * 未来前端资源和固定列表顺序造成的断言失败应交给 TestWriter 修正。
     */
    @Test
    void routesFutureResourceAssertionToTests() {
        SoftwareStudioWorkflowData data = data(
                FailureKind.TEST_ASSERTION,
                "FrontendContractTest ClassPathResource static/index.html should exist; JSON path \"$[0].title\"");

        var decision = service.decide(data);

        assertEquals(RepairTarget.TESTS, decision.target());
    }

    /**
     * Spring MVC 无法解析生产模板时必须修复实现，不能通过放宽测试隐藏缺页问题。
     */
    @Test
    void routesTemplateResolutionFailureToImplementation() {
        SoftwareStudioWorkflowData data = data(
                FailureKind.TEST_ASSERTION,
                "TemplateInputException: Error resolving template [article-detail], template might not exist");

        var decision = service.decide(data);

        assertEquals(RepairTarget.IMPLEMENTATION, decision.target());
    }

    /**
     * 前端契约测试把表单字段断言在错误页面时应重写测试，而不是扭曲生产页面。
     */
    @Test
    void routesFrontendContractAssertionToTests() {
        SoftwareStudioWorkflowData data = data(
                FailureKind.TEST_ASSERTION,
                "FrontendContractTest indexHtml_shouldContainExpectedLinksAndForms AssertionFailedError expected: <true>");

        var decision = service.decide(data);

        assertEquals(RepairTarget.TESTS, decision.target());
    }

    /**
     * 集成测试不得为数据库自增实体手工指定固定 ID。
     */
    @Test
    void routesGeneratedIdAssumptionToTests() {
        SoftwareStudioWorkflowData data = data(
                FailureKind.TEST_ASSERTION,
                "Model attribute article.title expected value but was null");
        data.codes.add(new SourceCode("src/main/java/com/example/Article.java", "java",
                "class Article { @GeneratedValue Long id; }"));
        data.codes.add(new SourceCode("src/test/java/com/example/ArticleIntegrationTest.java", "java",
                "@SpringBootTest class ArticleIntegrationTest { ArticleRepository repository; "
                        + "void setup() { Article article = new Article(); article.setId(1L); repository.save(article); } }"));

        var decision = service.decide(data);

        assertEquals(RepairTarget.TESTS, decision.target());
    }

    /**
     * 契约端点返回 404 属于实现缺口，不能因测试类名含 IntegrationTest 就改写测试。
     */
    @Test
    void routesEndpointStatusMismatchToImplementation() {
        SoftwareStudioWorkflowData data = data(
                FailureKind.TEST_ASSERTION,
                "PollResultsControllerTest Status expected:<200> but was:<404>");

        var decision = service.decide(data);

        assertEquals(RepairTarget.IMPLEMENTATION, decision.target());
    }

    /**
     * 未配置异常映射时，MockMvc 不会把抛出的异常自动转换成可断言的 500 响应。
     */
    @Test
    void routesUnhandledMockMvc500ExpectationToTests() {
        SoftwareStudioWorkflowData data = data(
                FailureKind.TEST_ASSERTION,
                "VotingControllerTest.castVote_shouldReturn500OnDuplicateVote <<< ERROR! "
                        + "jakarta.servlet.ServletException: Request processing failed: DuplicateVoteException");
        data.codes.add(new SourceCode(
                "src/test/java/com/example/VotingControllerTest.java",
                "java",
                "mockMvc.perform(request).andExpect(status().isInternalServerError());"));

        var decision = service.decide(data);

        assertEquals(RepairTarget.TESTS, decision.target());
    }

    /**
     * 环境错误必须停止修改源码，也不能调用任何修复 Agent。
     */
    @Test
    void stopsEnvironmentFailureWithoutLlm() {
        SoftwareStudioWorkflowData data = data(FailureKind.ENVIRONMENT, "Cannot connect to Docker daemon");

        var decision = service.decide(data);

        assertEquals(RepairTarget.ENVIRONMENT, decision.target());
        assertFalse(decision.retryable());
        assertFalse(decision.llmAllowed());
        assertTrue(data.repairStopRequested);
    }

    /**
     * 同一失败修复后再次出现时停止第二次重复调用。
     */
    @Test
    void stopsRepeatedFailureFingerprint() {
        SoftwareStudioWorkflowData data = data(FailureKind.MAIN_COMPILE, "App.java:[10,2] cannot find symbol");
        service.decide(data);
        data.pendingFixLog = "App.java:[11,2] cannot find symbol";

        var decision = service.decide(data);

        assertEquals(RepairTarget.STOP, decision.target());
        assertFalse(decision.llmAllowed());
        assertEquals(1, data.repeatedFailureStopCount);
    }

    /**
     * 回滚清空普通失败指纹后，原始问题应重新进入修复，而不是被当成第二次重复失败。
     */
    @Test
    void retriesBaselineFailureAfterRegressionRollbackReset() {
        SoftwareStudioWorkflowData data = data(
                FailureKind.TEST_DISCOVERY, "No @SpringBootTest was generated");
        service.decide(data);
        data.lastFailureFingerprint = null;
        data.repeatedFailureCount = 0;

        var decision = service.decide(data);

        assertEquals(RepairTarget.TESTS, decision.target());
        assertTrue(decision.llmAllowed());
        assertEquals(1, data.repeatedFailureCount);
        assertEquals(0, data.repeatedFailureStopCount);
    }

    private SoftwareStudioWorkflowData data(FailureKind kind, String log) {
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.pendingFailureKind = kind;
        data.pendingErrorType = kind.name();
        data.pendingFixLog = log;
        data.shouldFix = true;
        return data;
    }
}
