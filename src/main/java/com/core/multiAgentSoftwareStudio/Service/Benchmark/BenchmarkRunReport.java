package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import java.util.List;

/**
 * 一次基准运行的聚合指标。假成功率只在平台已宣称成功的样本中计算。
 */
public record BenchmarkRunReport(
        String startedAt,
        String finishedAt,
        List<BenchmarkModelInfo> models,
        BenchmarkRunConfiguration benchmarkConfig,
        List<BenchmarkCaseResult> cases,
        int platformSuccessCount,
        int independentQualityPassCount,
        int falseSuccessCount,
        Double falseSuccessRate,
        String reportPath) {
}
