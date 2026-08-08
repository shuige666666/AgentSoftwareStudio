package com.core.multiAgentSoftwareStudio.Model.Benchmark;

import com.core.multiAgentSoftwareStudio.Model.Metric.LlmUsageSnapshot;

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
        String workflowError) {
}
