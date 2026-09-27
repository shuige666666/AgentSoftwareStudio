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
        Map<String, String> requirementSha256,
        BenchmarkDevelopmentConfiguration development,
        String planningSourceProjectId,
        BenchmarkReplayMode replayMode) {

    public BenchmarkRunConfiguration {
        requirementSha256 = requirementSha256 == null ? Map.of() : Map.copyOf(requirementSha256);
    }

    public BenchmarkRunConfiguration(List<String> caseIds, int repetitions,
                                     BenchmarkRepairBudgetConfiguration repairBudget,
                                     String gitCommit, boolean gitDirty, String workingTreeFingerprint) {
        this(caseIds, repetitions, repairBudget, gitCommit, gitDirty, workingTreeFingerprint, Map.of(), null,
                null, BenchmarkReplayMode.SAVED_MILESTONES);
    }

    public BenchmarkRunConfiguration(List<String> caseIds, int repetitions,
                                     BenchmarkRepairBudgetConfiguration repairBudget,
                                     String gitCommit, boolean gitDirty, String workingTreeFingerprint,
                                     Map<String, String> requirementSha256) {
        this(caseIds, repetitions, repairBudget, gitCommit, gitDirty,
                workingTreeFingerprint, requirementSha256, null, null,
                BenchmarkReplayMode.SAVED_MILESTONES);
    }

    public BenchmarkRunConfiguration(List<String> caseIds, int repetitions,
                                     BenchmarkRepairBudgetConfiguration repairBudget,
                                     String gitCommit, boolean gitDirty, String workingTreeFingerprint,
                                     Map<String, String> requirementSha256,
                                     BenchmarkDevelopmentConfiguration development) {
        this(caseIds, repetitions, repairBudget, gitCommit, gitDirty,
                workingTreeFingerprint, requirementSha256, development, null,
                BenchmarkReplayMode.SAVED_MILESTONES);
    }

    public BenchmarkRunConfiguration(List<String> caseIds, int repetitions,
                                     BenchmarkRepairBudgetConfiguration repairBudget,
                                     String gitCommit, boolean gitDirty, String workingTreeFingerprint,
                                     Map<String, String> requirementSha256,
                                     BenchmarkDevelopmentConfiguration development,
                                     String planningSourceProjectId) {
        this(caseIds, repetitions, repairBudget, gitCommit, gitDirty, workingTreeFingerprint,
                requirementSha256, development, planningSourceProjectId,
                BenchmarkReplayMode.SAVED_MILESTONES);
    }
}
