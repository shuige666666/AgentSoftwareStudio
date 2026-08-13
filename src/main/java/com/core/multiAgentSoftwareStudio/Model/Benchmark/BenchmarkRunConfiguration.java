package com.core.multiAgentSoftwareStudio.Model.Benchmark;

import java.util.List;

/**
 * 保存影响基准可复现性的运行参数和源码版本。
 */
public record BenchmarkRunConfiguration(
        List<String> caseIds,
        int repetitions,
        BenchmarkRepairBudgetConfiguration repairBudget,
        String gitCommit,
        boolean gitDirty,
        String workingTreeFingerprint) {
}
