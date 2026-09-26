package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowExecutionFailure;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRunResult;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentStopReason;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceCheckpointService;
import com.core.multiAgentSoftwareStudio.Service.Generation.BatchGenerationService;
import com.core.multiAgentSoftwareStudio.Service.Generation.ProjectScaffoldService;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.BatchPlanNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.PersistenceNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.PlanningNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.SlicePlanNodeService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SoftwareStudioWorkflowServiceTest {

    @Test
    void completedDevelopmentUsesItsFinalCheckpointWithoutOuterVerification() {
        Fixture fixture = fixture();
        when(fixture.generation.generateProject(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.success = true;
            return run(true);
        });
        var result = fixture.service.generateProjectWithResult("test", message -> { });
        assertTrue(result.platformSuccess());
        verify(fixture.checkpoint, never()).verifyCandidate(any(), any());
    }

    @Test
    void incompleteDevelopmentGetsOneIndependentFinalVerification() {
        Fixture fixture = fixture();
        when(fixture.generation.generateProject(any(), any())).thenReturn(run(false));
        var result = fixture.service.generateProjectWithResult("test", message -> { });
        assertFalse(result.platformSuccess());
        verify(fixture.checkpoint).verifyCandidate(any(), any());
    }

    @Test
    void preservesProjectPathWhenFallbackVerificationFails() {
        Fixture fixture = fixture();
        when(fixture.generation.generateProject(any(), any())).thenReturn(run(false));
        when(fixture.checkpoint.verifyCandidate(any(), any()))
                .thenThrow(new IllegalStateException("snapshot failed"));
        WorkflowExecutionFailure failure = assertThrows(WorkflowExecutionFailure.class,
                () -> fixture.service.generateProjectWithResult("test", message -> { }));
        assertTrue(failure.projectPath().contains("quality-first-workflow-test"));
    }

    private WorkspaceAgentRunResult run(boolean completed) {
        return new WorkspaceAgentRunResult(completed,
                completed ? WorkspaceAgentStopReason.COMPLETED : WorkspaceAgentStopReason.MODEL_TURN_LIMIT,
                2, 4, List.of(), "submit_checkpoint", "{}", "done");
    }

    private Fixture fixture() {
        PlanningNodeService planning = passthrough(PlanningNodeService.class);
        ProjectScaffoldService scaffold = passthrough(ProjectScaffoldService.class);
        SlicePlanNodeService slicePlan = passthrough(SlicePlanNodeService.class);
        BatchPlanNodeService batchPlan = passthrough(BatchPlanNodeService.class);
        BatchGenerationService generation = mock(BatchGenerationService.class);
        PersistenceNodeService persistence = mock(PersistenceNodeService.class);
        WorkspaceCheckpointService checkpoint = mock(WorkspaceCheckpointService.class);
        LlmUsageMetricsService metrics = mock(LlmUsageMetricsService.class);
        RunJournalService journal = new RunJournalService();
        when(persistence.initializeWorkspace(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.projectPath = "target/quality-first-workflow-test";
            return data;
        });
        when(persistence.persistPlanningArtifacts(any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        SoftwareStudioWorkflowService service = new SoftwareStudioWorkflowService(
                planning, scaffold, slicePlan, batchPlan, generation, persistence,
                checkpoint, metrics, journal);
        return new Fixture(service, generation, checkpoint);
    }

    private <T> T passthrough(Class<T> type) {
        return mock(type, invocation -> invocation.getArguments().length > 0
                && invocation.getArguments()[0] instanceof SoftwareStudioWorkflowData data ? data : null);
    }

    private record Fixture(SoftwareStudioWorkflowService service, BatchGenerationService generation,
                           WorkspaceCheckpointService checkpoint) { }
}
