package com.core.multiAgentSoftwareStudio.Model.Benchmark;

import java.util.List;

/**
 * 保存影响基准可复现性的运行参数和源码版本。
 */
public record BenchmarkRunConfiguration(
        List<String> caseIds,
        int maxRetries,
        String gitCommit,
        boolean gitDirty,
        String workingTreeFingerprint) {

    /**
     * 保留旧构造方式，已提交且无本地改动的调用方无需补充额外字段。
     */
    public BenchmarkRunConfiguration(List<String> caseIds, int maxRetries, String gitCommit) {
        this(caseIds, maxRetries, gitCommit, false, null);
    }
}
