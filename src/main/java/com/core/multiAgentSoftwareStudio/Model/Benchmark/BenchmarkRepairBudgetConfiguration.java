package com.core.multiAgentSoftwareStudio.Model.Benchmark;

/**
 * 保存基准运行采用的修复预算策略参数；兼容旧报告字段，但当前额度由完整项目共享。
 */
public record BenchmarkRepairBudgetConfiguration(
        String policyVersion,
        int maxProjectLlmRepairs,
        int extraRepairsBeyondSliceCount,
        int maxRepairsPerSlice,
        int reservedForFinalVerification) {
}
