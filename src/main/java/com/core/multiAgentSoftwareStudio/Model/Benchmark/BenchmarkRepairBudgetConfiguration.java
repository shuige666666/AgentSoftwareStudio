package com.core.multiAgentSoftwareStudio.Model.Benchmark;

/**
 * 保存基准运行采用的修复预算策略参数；单案例实际额度由规划出的切片数量决定。
 */
public record BenchmarkRepairBudgetConfiguration(
        String policyVersion,
        int maxProjectLlmRepairs,
        int extraRepairsBeyondSliceCount,
        int maxRepairsPerSlice,
        int reservedForFinalVerification) {
}
