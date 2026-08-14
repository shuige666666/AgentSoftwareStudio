package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityGateSeverity;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityPolicyFinding;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectProfile;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairBudget;
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
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
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
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        service.recordPreflight(data, new ProjectQualityPolicyResult(false, List.of(
                new QualityPolicyFinding(
                        "FRONTEND_PAYLOAD_FIELDS", QualityGateSeverity.BLOCK,
                        FailureKind.CONTRACT, "optionId does not match pollOptionId")), List.of()));

        var summary = service.summarize(data);

        assertEquals(1L, summary.failureCounts().get(FailureKind.CONTRACT));
        assertEquals(1L, summary.failedGateCounts().get("FRONTEND_PAYLOAD_FIELDS"));
    }

    /**
     * 当前项目级工具流程按最终接受结果聚合计划数和修复预算，不伪造逐切片首轮通过。
     */
    @Test
    void summarizesVerticalSliceDeliveryAndRepairBudget() {
        RunJournalService service = new RunJournalService();
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        ProjectContract emptyContract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                new DeliverySlice("poll", "Poll", List.of(), List.of(), emptyContract, List.of(), List.of()),
                new DeliverySlice("result", "Result", List.of(), List.of(), emptyContract, List.of(), List.of())),
                List.of(), "vertical-slice-test");
        data.repairBudget = RepairBudget.forSlicePlan(2);
        data.structure = new ProjectStructure("com.example", "spring boot mvc", "com.example.App", List.of());
        data.projectProfile = ProjectProfile.java17SpringBoot();
        data.generatedTestFileCount = 3;
        data.admittedTestFileCount = 3;
        data.acceptedTestFiles.add("src/test/java/com/example/PollTest.java");
        data.acceptedTestFiles.add("src/test/java/com/example/ResultTest.java");
        data.acceptedSliceIds.add("poll");
        data.acceptedSliceIds.add("result");
        data.skippedFutureTypeTestCount = 1;
        data.repairRollbackCount = 2;
        data.repeatedRegressionStopCount = 1;
        data.markRepairStopped("NO_CHANGE");
        service.recordSliceStarted(data);
        service.recordSliceAccepted(data);
        data.currentSliceIndex = 1;
        data.consumeRepairBudget();
        service.recordSliceStarted(data);
        service.recordSliceAccepted(data);
        service.recordRegressionFailure(data);

        var summary = service.summarize(data).sliceDelivery();

        assertEquals(2, summary.plannedSlices());
        assertEquals(2, summary.acceptedSlices());
        assertEquals(0, summary.firstPassAcceptedSlices());
        assertEquals(1, summary.regressionFailures());
        assertEquals(2, summary.repairRollbackCount());
        assertEquals(1, summary.repeatedRegressionStopCount());
        assertEquals(1, summary.repairBudgetUsed());
        assertEquals(4, summary.repairBudgetLimit());
        assertEquals(4, summary.sliceRepairSafetyLimit());
        assertEquals(1, summary.finalVerificationReserve());
        assertEquals(1, summary.sliceRepairCounts().get("result"));
        assertEquals("spring boot mvc", summary.declaredProjectType());
        assertEquals("SPRING_BOOT", summary.effectiveProjectType());
        assertEquals(3, summary.generatedTestFiles());
        assertEquals(3, summary.admittedTestFiles());
        assertEquals(2, summary.acceptedTestFiles());
        assertEquals(1, summary.skippedFutureTypeTests());
        assertEquals("NO_CHANGE", summary.repairStopReason());
    }
}
