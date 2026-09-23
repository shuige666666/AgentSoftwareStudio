package com.core.multiAgentSoftwareStudio.Model.Benchmark;

import java.util.List;
import java.util.Map;

/**
 * 保存影响基准可复现性的运行参数和源码版本。
 */
public record BenchmarkRunConfiguration(
        List<String> caseIds,
        int repetitions,
        BenchmarkRepairBudgetConfiguration repairBudget,
        String gitCommit,
        boolean gitDirty,
        String workingTreeFingerprint,
        Map<String, String> requirementSha256) {

    public BenchmarkRunConfiguration {
        requirementSha256 = requirementSha256 == null ? Map.of() : Map.copyOf(requirementSha256);
    }

    public BenchmarkRunConfiguration(List<String> caseIds, int repetitions,
                                     BenchmarkRepairBudgetConfiguration repairBudget,
                                     String gitCommit, boolean gitDirty, String workingTreeFingerprint) {
        this(caseIds, repetitions, repairBudget, gitCommit, gitDirty, workingTreeFingerprint, Map.of());
    }
}
