package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkReplayMode;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowExecutionFailure;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStepResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStage;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRunResult;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentStopReason;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceCheckpointService;
import com.core.multiAgentSoftwareStudio.Service.Generation.BatchGenerationService;
import com.core.multiAgentSoftwareStudio.Service.Generation.ProjectScaffoldService;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.PersistenceNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.PlanningNodeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.SlicePlanNodeService;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
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

    /** 普通新项目采用建议式；入口显式选择强制模式时不被默认值覆盖。 */
    @Test
    void generationUsesDefaultPolicyAndAllowsExplicitOrderedPolicy() {
        for (var policy : com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestonePolicy.values()) {
            var fixture = fixture();
            when(fixture.generation.generateProject(any(), any())).thenAnswer(invocation -> {
                SoftwareStudioWorkflowData data = invocation.getArgument(0);
                assertEquals(policy, data.milestonePolicy);
                return run(false);
            });
            if (policy == com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestonePolicy.ADVISORY) {
                fixture.service.generateProjectWithResult("same requirement", message -> { });
            } else {
                fixture.service.generateProjectWithResult("same requirement", null, policy, message -> { });
            }
        }
    }

    @Test
    void incompleteDevelopmentGetsOneIndependentFinalVerification() {
        Fixture fixture = fixture();
        when(fixture.generation.generateProject(any(), any())).thenReturn(run(false));
        var result = fixture.service.generateProjectWithResult("test", message -> { });
        assertFalse(result.platformSuccess());
        verify(fixture.checkpoint).verifyCandidate(any(), any());
    }

    /** 外层失败复核必须保存根因及源码版本，后续开发不能只收到 MODEL_TURN_LIMIT。 */
    @Test
    void preservesFinalFailureEvidenceAfterBudgetStop() {
        var fixture = fixture();
        when(fixture.generation.generateProject(any(), any())).thenReturn(run(false));
        fixture.service.generateProjectWithResult("test", ignored -> { });
        verify(fixture.milestones).recordFailure(any(), org.mockito.ArgumentMatchers.contains("Final verification sourceHash=saved-hash"));
        verify(fixture.milestones).recordFailure(any(), org.mockito.ArgumentMatchers.contains("NoSuchBeanDefinitionException: demo.Store"));
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

    /** 固定规划重放创建新候选与全新预算，不再次调用规划节点或继承旧源码。 */
    @Test
    void replaySavedPlanningStartsFreshDevelopment() {
        Fixture fixture = fixture();
        SoftwareStudioWorkflowData saved = new SoftwareStudioWorkflowData("same requirement");
        saved.projectPath = "old-candidate";
        saved.developmentModelTurnsUsed = 24;
        saved.developmentToolCallsUsed = 24;
        saved.sliceDeliveryPlan = new com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan(
                List.of(), List.of(), "frozen-plan");
        when(fixture.resume.load("source-project")).thenReturn(saved);
        when(fixture.generation.generateProject(any(), any())).thenAnswer(invocation -> {
            SoftwareStudioWorkflowData data = invocation.getArgument(0);
            data.success = true;
            return run(true);
        });

        var result = fixture.service.generateFromSavedPlanningWithResult(
                "source-project", "same requirement", message -> { });

        ArgumentCaptor<SoftwareStudioWorkflowData> captured =
                ArgumentCaptor.forClass(SoftwareStudioWorkflowData.class);
        verify(fixture.generation).generateProject(captured.capture(), any());
        SoftwareStudioWorkflowData replay = captured.getValue();
        assertTrue(result.platformSuccess());
        assertEquals("target/quality-first-workflow-test", replay.projectPath);
        assertEquals(0, replay.developmentModelTurnsUsed);
        assertEquals(0, replay.developmentToolCallsUsed);
        assertSame(saved.sliceDeliveryPlan, replay.sliceDeliveryPlan);
        verify(fixture.planning, never()).requirements(any(), any());
        verify(fixture.planning, never()).design(any(), any());
        verify(fixture.slicePlan, never()).execute(any(), any());
    }

    /** 不能把另一需求的规划产物冒充为当前固定案例的对照输入。 */
    @Test
    void replayRejectsPlanningForDifferentRequirement() {
        Fixture fixture = fixture();
        when(fixture.resume.load("source-project"))
                .thenReturn(new SoftwareStudioWorkflowData("different requirement"));

        assertThrows(IllegalArgumentException.class,
                () -> fixture.service.generateFromSavedPlanningWithResult(
                        "source-project", "current requirement", message -> { }));

        verify(fixture.generation, never()).generateProject(any(), any());
    }

    /** 两种对照均重建当前切片；连续方式不发放逐任务状态。 */
    @Test
    void comparisonReplaysSameDesignWithSelectableDevelopmentProtocol() {
        for (BenchmarkReplayMode mode : List.of(BenchmarkReplayMode.CURRENT_MILESTONES,
                BenchmarkReplayMode.ADVISORY_MILESTONES,
                BenchmarkReplayMode.CONTINUOUS)) {
            Fixture fixture = fixture();
            when(fixture.resume.load("source-project"))
                    .thenReturn(new SoftwareStudioWorkflowData("same requirement"));
            when(fixture.generation.generateProject(any(), any())).thenReturn(run(false));

            fixture.service.generateFromSavedPlanningWithResult(
                    "source-project", "same requirement", mode, message -> { });

            verify(fixture.planning, never()).requirements(any(), any());
            verify(fixture.planning, never()).design(any(), any());
            verify(fixture.slicePlan).execute(any(), any());
            if (mode == BenchmarkReplayMode.CONTINUOUS) {
                verify(fixture.milestones, never()).initialize(any());
            } else {
                ArgumentCaptor<SoftwareStudioWorkflowData> captured = ArgumentCaptor.forClass(SoftwareStudioWorkflowData.class);
                verify(fixture.milestones).initialize(captured.capture());
                assertEquals(mode == BenchmarkReplayMode.ADVISORY_MILESTONES
                        ? com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestonePolicy.ADVISORY
                        : com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestonePolicy.ORDERED,
                        captured.getValue().milestonePolicy);
            }
        }
    }

    private WorkspaceAgentRunResult run(boolean completed) {
        return new WorkspaceAgentRunResult(completed,
                completed ? WorkspaceAgentStopReason.COMPLETED : WorkspaceAgentStopReason.MODEL_TURN_LIMIT,
                2, 4, List.of(), "submit_checkpoint", "{}", "done");
    }

    /** 外层最终门禁通过时，只有顺序策略仍要求逐项接受；建议式不能因此假失败。 */
    @Test
    void finalPassingCandidateMayHavePendingAdvisoryTasks() {
        for (var policy : com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestonePolicy.values()) {
            var fixture = fixture();
            when(fixture.generation.generateProject(any(), any())).thenAnswer(invocation -> {
                SoftwareStudioWorkflowData data = invocation.getArgument(0);
                var tasks = List.of(new com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestoneState.Task(
                        "a", "a", List.of(), false, "", "", ""),
                        new com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestoneState.Task(
                        "b", "b", List.of(), false, "", "", ""));
                data.milestoneState = new com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestoneState(
                        2, tasks, 0, 0, "", policy, "");
                return run(false);
            });
            when(fixture.checkpoint.verifyCandidate(any(), any())).thenAnswer(invocation -> {
                SoftwareStudioWorkflowData data = invocation.getArgument(0);
                data.success = true;
                var passed = mock(WorkspaceCheckpointService.CheckpointResult.class);
                when(passed.allowCompletion()).thenReturn(true);
                return passed;
            });
            var result = fixture.service.generateProjectWithResult("same requirement", message -> { });
            assertEquals(policy == com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestonePolicy.ADVISORY,
                    result.platformSuccess());
        }
    }

    private Fixture fixture() {
        PlanningNodeService planning = passthrough(PlanningNodeService.class);
        ProjectScaffoldService scaffold = passthrough(ProjectScaffoldService.class);
        SlicePlanNodeService slicePlan = passthrough(SlicePlanNodeService.class);
        DevelopmentMilestoneService milestones = mock(DevelopmentMilestoneService.class);
        DevelopmentResumeService resume = mock(DevelopmentResumeService.class);
        BatchGenerationService generation = mock(BatchGenerationService.class);
        PersistenceNodeService persistence = mock(PersistenceNodeService.class);
        WorkspaceCheckpointService checkpoint = mock(WorkspaceCheckpointService.class);
        var failed = new WorkspaceCheckpointService.CheckpointResult("final", "FAILED", "saved-hash", "snapshot", "report.json",
                VerificationStepResult.notRun(VerificationStage.BUILD),
                VerificationStepResult.notRun(VerificationStage.TEST),
                false, List.of(), "NoSuchBeanDefinitionException: demo.Store", false, false);
        when(checkpoint.verifyCandidate(any(), any())).thenReturn(failed);
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
                planning, scaffold, slicePlan, milestones, resume, generation, persistence,
                checkpoint, metrics, journal);
        return new Fixture(service, generation, checkpoint, planning, slicePlan, resume, milestones);
    }

    private <T> T passthrough(Class<T> type) {
        return mock(type, invocation -> invocation.getArguments().length > 0
                && invocation.getArguments()[0] instanceof SoftwareStudioWorkflowData data ? data : null);
    }

    private record Fixture(SoftwareStudioWorkflowService service, BatchGenerationService generation,
                           WorkspaceCheckpointService checkpoint, PlanningNodeService planning,
                           SlicePlanNodeService slicePlan, DevelopmentResumeService resume,
                           DevelopmentMilestoneService milestones) { }
}
