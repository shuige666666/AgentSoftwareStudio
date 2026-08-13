package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStage;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStepResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationResultServiceTest {

    private final VerificationResultService service = new VerificationResultService();

    /**
     * 测试命令即使退出码为零，没有发现测试摘要也不能判定成功。
     */
    @Test
    void rejectsSuccessfulCommandWhenNoTestsWereDiscovered() {
        SandboxExecutionResult execution = new SandboxExecutionResult(
                "mvn test", 0, "[INFO] No tests to run.\n[INFO] BUILD SUCCESS", 100, false, null);

        VerificationStepResult result = service.toStep(VerificationStage.TEST, execution);

        assertFalse(result.passed());
        assertEquals(FailureKind.TEST_DISCOVERY, result.failureKind());
    }

    /**
     * 只有命令成功且测试真实执行并全绿时，测试阶段才通过。
     */
    @Test
    void acceptsExecutedGreenTests() {
        SandboxExecutionResult execution = new SandboxExecutionResult(
                "mvn test", 0,
                "Tests run: 4, Failures: 0, Errors: 0, Skipped: 1\nBUILD SUCCESS",
                100, false, null);

        VerificationStepResult result = service.toStep(VerificationStage.TEST, execution);

        assertTrue(result.passed());
        assertEquals(4, result.testSummary().testsRun());
        assertEquals(FailureKind.NONE, result.failureKind());
    }

    /**
     * POM 模型错误必须分类为工程 Profile 错误，不能落入无测试摘要的假成功分支。
     */
    @Test
    void classifiesInvalidPomAsBuildProfileFailure() {
        SandboxExecutionResult execution = new SandboxExecutionResult(
                "mvn test", 1,
                "Some problems were encountered while processing the POMs: dependencies.dependency.version is missing",
                100, false, null);

        VerificationStepResult result = service.toStep(VerificationStage.TEST, execution);

        assertFalse(result.passed());
        assertEquals(FailureKind.BUILD_PROFILE, result.failureKind());
    }

    /**
     * 测试源码编译失败应与主源码编译失败分开统计和路由。
     */
    @Test
    void classifiesTestCompilationFailure() {
        SandboxExecutionResult execution = new SandboxExecutionResult(
                "mvn test", 1,
                "maven-compiler-plugin:testCompile COMPILATION ERROR src/test/java/AppTest.java cannot find symbol",
                100, false, null);

        VerificationStepResult result = service.toStep(VerificationStage.TEST, execution);

        assertEquals(FailureKind.TEST_COMPILE, result.failureKind());
    }

    /**
     * Maven 生命周期中的 testCompile 文本不能覆盖已经执行出来的断言失败摘要。
     */
    @Test
    void classifiesAssertionFailureBeforeTestCompileLifecycleText() {
        SandboxExecutionResult execution = new SandboxExecutionResult(
                "mvn test", 1,
                """
                        [INFO] --- maven-compiler-plugin:3.13.0:testCompile (default-testCompile) @ blog ---
                        Tests run: 21, Failures: 1, Errors: 0, Skipped: 0
                        [ERROR] There are test failures.
                        """,
                100, false, null);

        VerificationStepResult result = service.toStep(VerificationStage.TEST, execution);

        assertEquals(FailureKind.TEST_ASSERTION, result.failureKind());
    }

    /**
     * Surefire 同时打印逐类和最终总计时，只采用最后一个总计，防止测试数量翻倍。
     */
    @Test
    void usesFinalSurefireSummaryWithoutDoubleCounting() {
        var summary = service.parseTestSummary("""
                Tests run: 2, Failures: 0, Errors: 0, Skipped: 0 -- in FirstTest
                Tests run: 3, Failures: 0, Errors: 0, Skipped: 0 -- in SecondTest
                Results:
                Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
                """);

        assertEquals(5, summary.testsRun());
        assertTrue(summary.executedAndGreen());
    }

    /**
     * Mockito 使用方式错误属于测试代码自身问题，不能路由去修改生产实现。
     */
    @Test
    void classifiesMockitoMisuseAsTestCodeFailure() {
        SandboxExecutionResult execution = new SandboxExecutionResult(
                "mvn test", 1,
                """
                        Tests run: 1, Failures: 0, Errors: 1, Skipped: 0
                        org.mockito.exceptions.misusing.InvalidUseOfMatchersException:
                        Invalid use of argument matchers!
                        """,
                100, false, null);

        VerificationStepResult result = service.toStep(VerificationStage.TEST, execution);

        assertEquals(FailureKind.TEST_CODE, result.failureKind());
    }
}
