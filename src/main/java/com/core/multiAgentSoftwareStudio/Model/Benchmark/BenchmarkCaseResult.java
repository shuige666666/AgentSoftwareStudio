package com.core.multiAgentSoftwareStudio.Model.Benchmark;

import com.core.multiAgentSoftwareStudio.Model.Metric.LlmUsageSnapshot;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowRunSummary;

/**
 * 单个真实 LLM 基准任务的结果。manualVerdict 暂时保留人工抽检入口，默认未审查。
 */
public record BenchmarkCaseResult(
        String caseId,
        boolean platformSuccess,
        boolean independentQualityPassed,
        String manualVerdict,
        String projectPath,
        long elapsedMillis,
        BenchmarkQualityResult quality,
        LlmUsageSnapshot usage,
        int attemptsUsed,
        FailureKind finalFailureKind,
        int validationWarningCount,
        BenchmarkVerificationSummary verificationSummary,
        WorkflowRunSummary runSummary,
        String workflowError) {
}
