package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityGateSeverity;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityPolicyFinding;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairDecision;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairTarget;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RunJournalServiceTest {

    /**
     * Journal 对外只聚合事件数量和失败分类，不暴露单次完整错误日志。
     */
    @Test
    void summarizesVerificationAndRepairEvents() {
        RunJournalService service = new RunJournalService();
        VerificationResultService verificationService = new VerificationResultService();
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test", 3);
        var build = verificationService.toStep(
                VerificationStage.BUILD,
                new SandboxExecutionResult("mvn package", 1, "COMPILATION ERROR", 1, false, null));
        service.recordVerification(data, build);
        data.pendingFailureKind = FailureKind.MAIN_COMPILE;
        service.recordRepair(data,
                new RepairDecision(RepairTarget.IMPLEMENTATION, true, true, "fingerprint", "route"),
                true,
                List.of("src/main/java/App.java"),
                "changed");

        var summary = service.summarize(data);

        assertEquals(1, summary.verificationSteps());
        assertEquals(1, summary.repairAttempts());
        assertEquals(1L, summary.failureCounts().get(FailureKind.MAIN_COMPILE));
        assertEquals(1L, summary.repairTargetCounts().get(RepairTarget.IMPLEMENTATION));
    }

    /**
     * 报告只聚合失败门禁的稳定 ID，不携带具体源码或完整诊断证据。
     */
    @Test
    void summarizesFailedGateIdsWithoutEvidence() {
        RunJournalService service = new RunJournalService();
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test", 3);
        service.recordPreflight(data, new ProjectQualityPolicyResult(false, List.of(
                new QualityPolicyFinding(
                        "FRONTEND_PAYLOAD_FIELDS", QualityGateSeverity.BLOCK,
                        FailureKind.CONTRACT, "optionId does not match pollOptionId")), List.of()));

        var summary = service.summarize(data);

        assertEquals(1L, summary.failureCounts().get(FailureKind.CONTRACT));
        assertEquals(1L, summary.failedGateCounts().get("FRONTEND_PAYLOAD_FIELDS"));
    }
}
