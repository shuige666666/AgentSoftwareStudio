package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkCase;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkQualityResult;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkReplayMode;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmUsageSnapshot;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowExecutionFailure;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowExecutionResult;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BenchmarkRunnerFailureTest {
    private final BenchmarkQualityEvaluator quality = mock(BenchmarkQualityEvaluator.class);
    private final BenchmarkBehaviorEvaluator behavior = mock(BenchmarkBehaviorEvaluator.class);
    private final SoftwareStudioWorkflowService workflow = mock(SoftwareStudioWorkflowService.class);
    private final LlmUsageMetricsService metrics = mock(LlmUsageMetricsService.class);
    private final BenchmarkRunnerService runner = new BenchmarkRunnerService(
            null, quality, behavior, workflow, metrics, new ObjectMapper(), "https://example.com",
            new com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig());
    private final BenchmarkCase benchmarkCase = new BenchmarkCase(
            "url-shortener-behavior-v1", "short", "prompt", Set.of());

    /**
     * 工作流末尾异常时，报告仍应指向已经生成的磁盘候选。
     */
    @Test
    void keepsDiagnosticReportWhenFailureBehaviorJudgeAlsoThrows() {
        when(workflow.generateProjectWithResult(any(), any())).thenThrow(
                new WorkflowExecutionFailure("candidate", new IllegalStateException("checkpoint failed")));
        when(behavior.evaluate(any(), any())).thenThrow(new IllegalStateException("judge failed"));

        var result = runner.runCase(benchmarkCase, 1, 1, 1, 1);

        assertEquals("candidate", result.projectPath());
        assertTrue(result.workflowError().contains("checkpoint failed"));
        assertEquals("ENVIRONMENT_ERROR", result.behavior().status());
    }

    @Test
    void preservesProjectPathAfterWorkflowException() {
        when(workflow.generateProjectWithResult(any(), any())).thenThrow(
                new WorkflowExecutionFailure("candidate", new UnsupportedOperationException("immutable")));
        when(behavior.evaluate(any(), any())).thenReturn(BenchmarkBehaviorResult.notRun("startup failed"));

        var result = runner.runCase(benchmarkCase, 1, 1, 1, 1);

        assertEquals("candidate", result.projectPath());
        assertTrue(result.workflowError().contains("UnsupportedOperationException"));
    }

    /** 固定规划样本仍进入普通判卷路径，开发入口复用已保存规划。 */
    @Test
    void routesFrozenPlanningCaseToReplayWorkflow() {
        when(workflow.generateFromSavedPlanningWithResult(any(), any(), any())).thenThrow(
                new WorkflowExecutionFailure("replay-candidate", new IllegalStateException("replay stopped")));

        var result = runner.runCase(benchmarkCase, 1, 1, 1, 1, "source-project");

        assertEquals("replay-candidate", result.projectPath());
        verify(workflow).generateFromSavedPlanningWithResult(
                org.mockito.ArgumentMatchers.eq("source-project"),
                org.mockito.ArgumentMatchers.eq("prompt"), any());
        verify(workflow, never()).generateProjectWithResult(any(), any());
    }

    /** 对照模式明确传给工作流，且仍沿用同一独立判卷入口。 */
    @Test
    void routesContinuousComparisonToReplayWorkflow() {
        when(workflow.generateFromSavedPlanningWithResult(any(), any(), any(), any())).thenThrow(
                new WorkflowExecutionFailure("continuous-candidate", new IllegalStateException("stopped")));

        var result = runner.runCase(benchmarkCase, 1, 1, 1, 1,
                "source-project", BenchmarkReplayMode.CONTINUOUS);

        assertEquals("continuous-candidate", result.projectPath());
        verify(workflow).generateFromSavedPlanningWithResult(
                org.mockito.ArgumentMatchers.eq("source-project"),
                org.mockito.ArgumentMatchers.eq("prompt"),
                org.mockito.ArgumentMatchers.eq(BenchmarkReplayMode.CONTINUOUS), any());
    }

    /**
     * 独立判卷自身异常不能把已完成生成的路径和用量抹掉。
     */
    @Test
    void preservesWorkflowEvidenceAfterBehaviorException() {
        LlmUsageSnapshot usage = new LlmUsageSnapshot(1, 1, 0, 10, 0, 5, 3, 8, false);
        WorkflowExecutionResult execution = new WorkflowExecutionResult(
                List.of(), true, "candidate", "", "", List.of(), "", 1, usage);
        when(workflow.generateProjectWithResult(any(), any())).thenReturn(execution);
        when(quality.evaluate(any(), any())).thenReturn(new BenchmarkQualityResult(true, List.of()));
        when(behavior.evaluate(any(), any())).thenThrow(new UnsupportedOperationException("judge failed"));

        var result = runner.runCase(benchmarkCase, 1, 1, 1, 1);

        assertEquals("candidate", result.projectPath());
        assertEquals(1, result.usage().calls());
        assertEquals("ENVIRONMENT_ERROR", result.behavior().status());
        assertTrue(result.workflowError().startsWith("BEHAVIOR"));
    }
}
