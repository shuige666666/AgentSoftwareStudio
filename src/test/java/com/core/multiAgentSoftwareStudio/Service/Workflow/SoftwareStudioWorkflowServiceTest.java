package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Service.Generation.BatchGenerationService;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Repair.ProjectRepairService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.ArchitectureNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.ContractNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.EvaluationNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.FrontendReviewNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.PersistenceNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.PreflightValidationNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.RequirementNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.SliceAcceptanceNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.SlicePlanNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.TestGenerationNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.VerificationNodeService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoftwareStudioWorkflowServiceTest {

    /**
     * 主图必须逐片完成生成和验收，全部切片结束后额外执行一次最终全量验证。
     */
    @Test
    void deliversSlicesSequentiallyThenRunsFinalVerification() {
        RequirementNodeService requirement = passthrough(RequirementNodeService.class);
        ArchitectureNodeService architecture = passthrough(ArchitectureNodeService.class);
        ContractNodeService contractNode = passthrough(ContractNodeService.class);
        SlicePlanNodeService slicePlan = mock(SlicePlanNodeService.class);
        BatchGenerationService generation = mock(BatchGenerationService.class);
        TestGenerationNodeService testGeneration = mock(TestGenerationNodeService.class);
        FrontendReviewNodeService frontendReview = passthrough(FrontendReviewNodeService.class);
        PreflightValidationNodeService preflight = mock(PreflightValidationNodeService.class);
        PersistenceNodeService persistence = mock(PersistenceNodeService.class);
        VerificationNodeService verification = passthrough(VerificationNodeService.class);
        EvaluationNodeService evaluation = mock(EvaluationNodeService.class);
        ProjectRepairService repair = mock(ProjectRepairService.class);
        RunJournalService journal = new RunJournalService();
        SliceAcceptanceNodeService acceptance = new SliceAcceptanceNodeService(journal);
        LlmUsageMetricsService metrics = mock(LlmUsageMetricsService.class);

        ProjectContract emptyContract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        when(slicePlan.execute(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                    new DeliverySlice("poll", "Poll", List.of(), List.of(), emptyContract, List.of(), List.of()),
                    new DeliverySlice("result", "Result", List.of(), List.of(), emptyContract, List.of("poll"), List.of())),
                    List.of(), "test-policy");
            journal.recordSliceStarted(data);
            return data;
        });
        when(persistence.initializeWorkspace(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.projectPath = "target/workflow-graph-test";
            return data;
        });
        doAnswer(invocation -> null).when(generation).generateSlice(any(), any());
        when(testGeneration.executeCurrentSlice(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.currentSliceTestFiles.add("src/test/java/" + data.currentSliceId() + "Test.java");
            return data;
        });
        when(preflight.execute(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.preflightPassed = true;
            data.shouldFix = false;
            return data;
        });
        when(persistence.persistCurrentSlice(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.currentSlicePersisted = true;
            return data;
        });
        when(evaluation.execute(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            if (data.finalVerificationStarted) {
                data.success = true;
            } else {
                data.currentSliceVerified = true;
            }
            data.shouldFix = false;
            return data;
        });

        SoftwareStudioWorkflowService service = new SoftwareStudioWorkflowService(
                requirement, architecture, contractNode, slicePlan, generation, testGeneration,
                frontendReview, preflight, persistence, verification, evaluation, acceptance,
                repair, metrics, journal);

        var result = service.generateProjectWithResult("test", message -> { });

        assertTrue(result.platformSuccess());
        assertEquals(2, result.runSummary().sliceDelivery().acceptedSlices());
        verify(generation, times(2)).generateSlice(any(), any());
        verify(testGeneration, times(2)).executeCurrentSlice(any(), any());
        verify(persistence, times(2)).persistCurrentSlice(any(), any());
        verify(verification, times(3)).run(any(), any());
        verify(preflight, times(3)).execute(any(), any());
        verify(repair, never()).repair(any(), any());
    }

    private <T> T passthrough(Class<T> type) {
        return mock(type, invocation -> invocation.getArguments().length > 0
                && invocation.getArguments()[0] instanceof SoftwareStudioWorkflowData data ? data : null);
    }
}
