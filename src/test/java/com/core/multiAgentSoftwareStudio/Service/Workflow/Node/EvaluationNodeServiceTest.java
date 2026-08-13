package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityGateSeverity;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityPolicyFinding;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStage;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workflow.VerificationResultService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Repair.RepairRegressionGuardService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvaluationNodeServiceTest {

    private final VerificationResultService resultService = new VerificationResultService();

    /**
     * 构建失败时不应继续执行测试，也不能被旧日志中的成功文本覆盖。
     */
    @Test
    void stopsAtFailedBuild() {
        VerificationNodeService verificationNodeService = mock(VerificationNodeService.class);
        EvaluationNodeService service = new EvaluationNodeService(verificationNodeService, mock(RunJournalService.class));
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        var build = resultService.toStep(
                VerificationStage.BUILD,
                new SandboxExecutionResult("mvn package", 1,
                        "Some problems were encountered while processing the POMs", 1, false, null));
        data.verificationResult = VerificationResult.afterBuild(build);
        data.executionResult = "BUILD SUCCESS";

        service.execute(data, message -> {
        });

        assertFalse(data.success);
        assertEquals(FailureKind.BUILD_PROFILE, data.pendingFailureKind);
        verify(verificationNodeService, never()).runTests(data);
    }

    /**
     * 候选修复后的验证失败必须先经过回归保护，再决定是否继续消耗剩余修复额度。
     */
    @Test
    void evaluatesRepairCandidateBeforeRetryRouting() {
        VerificationNodeService verificationNodeService = mock(VerificationNodeService.class);
        RepairRegressionGuardService regressionGuard = mock(RepairRegressionGuardService.class);
        EvaluationNodeService service = new EvaluationNodeService(
                verificationNodeService, mock(RunJournalService.class), regressionGuard);
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        var build = resultService.toStep(
                VerificationStage.BUILD,
                new SandboxExecutionResult("mvn package", 1, "cannot find symbol", 1, false, null));
        data.verificationResult = VerificationResult.afterBuild(build);

        service.execute(data, message -> { });

        verify(regressionGuard).rollbackIfRegressed(any(), any());
        assertTrue(data.shouldFix);
    }

    /**
     * 测试命令退出码为零但没有测试摘要时，必须进入修复而不是假成功。
     */
    @Test
    void rejectsMissingTestSummary() {
        VerificationNodeService verificationNodeService = mock(VerificationNodeService.class);
        EvaluationNodeService service = new EvaluationNodeService(verificationNodeService, mock(RunJournalService.class));
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        var build = resultService.toStep(
                VerificationStage.BUILD,
                new SandboxExecutionResult("mvn package", 0, "BUILD SUCCESS", 1, false, null));
        var test = resultService.toStep(
                VerificationStage.TEST,
                new SandboxExecutionResult("mvn test", 0, "No tests to run\nBUILD SUCCESS", 1, false, null));
        data.verificationResult = VerificationResult.afterBuild(build);
        data.testResult = "No tests to run\nBUILD SUCCESS";
        when(verificationNodeService.runTests(data)).thenReturn(test);

        service.execute(data, message -> {
        });

        assertFalse(data.success);
        assertTrue(data.shouldFix);
        assertEquals(FailureKind.TEST_DISCOVERY, data.pendingFailureKind);
    }

    /**
     * 构建和测试均以结构化证据通过时，工作流才可声明成功。
     */
    @Test
    void acceptsStructuredBuildAndGreenTests() {
        VerificationNodeService verificationNodeService = mock(VerificationNodeService.class);
        EvaluationNodeService service = new EvaluationNodeService(verificationNodeService, mock(RunJournalService.class));
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        var build = resultService.toStep(
                VerificationStage.BUILD,
                new SandboxExecutionResult("mvn package", 0, "BUILD SUCCESS", 1, false, null));
        var test = resultService.toStep(
                VerificationStage.TEST,
                new SandboxExecutionResult("mvn test", 0,
                        "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS",
                        1, false, null));
        data.verificationResult = VerificationResult.afterBuild(build);
        data.testResult = "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0";
        when(verificationNodeService.runTests(data)).thenReturn(test);

        service.execute(data, message -> {
        });

        assertTrue(data.success);
        assertFalse(data.shouldFix);
        assertEquals(FailureKind.NONE, data.verificationResult.primaryFailureKind());
    }

    /**
     * 技术验证通过后仍需执行语义契约门禁，不能把前后端字段错位声明为成功。
     */
    @Test
    void routesDeferredContractBlockerAfterGreenTests() {
        VerificationNodeService verificationNodeService = mock(VerificationNodeService.class);
        EvaluationNodeService service = new EvaluationNodeService(verificationNodeService, mock(RunJournalService.class));
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        var build = resultService.toStep(
                VerificationStage.BUILD,
                new SandboxExecutionResult("mvn package", 0, "BUILD SUCCESS", 1, false, null));
        var test = resultService.toStep(
                VerificationStage.TEST,
                new SandboxExecutionResult("mvn test", 0,
                        "Tests run: 2, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS",
                        1, false, null));
        data.verificationResult = VerificationResult.afterBuild(build);
        data.qualityPolicyResult = new ProjectQualityPolicyResult(false, java.util.List.of(
                new QualityPolicyFinding(
                        "CONTRACT_VALIDATION", QualityGateSeverity.BLOCK,
                        FailureKind.CONTRACT, "frontend payload mismatch")), java.util.List.of());
        when(verificationNodeService.runTests(data)).thenReturn(test);

        service.execute(data, message -> {
        });

        assertFalse(data.success);
        assertTrue(data.shouldFix);
        assertEquals(FailureKind.CONTRACT, data.pendingFailureKind);
    }

    /**
     * 测试行为失败与确定性契约阻塞并存时应先修生产契约，避免连续把预算消耗在重写测试上。
     */
    @Test
    void prioritizesContractBlockerOverTestCodeFailure() {
        VerificationNodeService verificationNodeService = mock(VerificationNodeService.class);
        EvaluationNodeService service = new EvaluationNodeService(
                verificationNodeService, mock(RunJournalService.class));
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        var build = resultService.toStep(
                VerificationStage.BUILD,
                new SandboxExecutionResult("mvn package", 0, "BUILD SUCCESS", 1, false, null));
        var test = new com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStepResult(
                VerificationStage.TEST,
                com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStatus.FAILED,
                FailureKind.TEST_CODE,
                "mvn test", 1, 1,
                new com.core.multiAgentSoftwareStudio.Model.Workflow.TestSummary(4, 1, 0, 0, true),
                "ControllerTest expected 302 but was 404");
        data.verificationResult = VerificationResult.afterBuild(build);
        data.testResult = "ControllerTest expected 302 but was 404";
        data.qualityPolicyResult = new ProjectQualityPolicyResult(false, java.util.List.of(
                new QualityPolicyFinding(
                        "DUPLICATE_CONTROLLER_MAPPING", QualityGateSeverity.BLOCK,
                        FailureKind.CONTRACT, "GET /{shortCode} has two owners")), java.util.List.of());
        when(verificationNodeService.runTests(data)).thenReturn(test);

        service.execute(data, message -> { });

        assertTrue(data.shouldFix);
        assertEquals(FailureKind.CONTRACT, data.pendingFailureKind);
        assertTrue(data.pendingFixLog.contains("DUPLICATE_CONTROLLER_MAPPING"));
        assertTrue(data.pendingFixLog.contains("expected 302 but was 404"));
    }
}
