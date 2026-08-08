package com.core.multiAgentSoftwareStudio.Model.Benchmark;

import java.util.List;
import java.util.Map;

/**
 * 一次基准运行的聚合指标。假成功率只在平台已宣称成功的样本中计算。
 */
public record BenchmarkRunReport(
        String startedAt,
        String finishedAt,
        Map<String, BenchmarkModelInfo> models,
        List<AgentModelAssignment> agentModelAssignments,
        BenchmarkRunConfiguration benchmarkConfig,
        List<BenchmarkCaseResult> cases,
        int platformSuccessCount,
        int independentQualityPassCount,
        int falseSuccessCount,
        Double falseSuccessRate,
        String reportPath) {
}
