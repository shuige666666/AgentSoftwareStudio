package com.core.multiAgentSoftwareStudio.Model.Benchmark;

import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationResult;

import java.io.Serializable;

/**
 * 基准报告中的验证摘要，只保存退出码、测试总量和分类，不写入完整执行日志。
 */
public record BenchmarkVerificationSummary(
        Integer buildExitCode,
        Integer testExitCode,
        boolean buildPassed,
        boolean testPassed,
        int testsRun,
        int failures,
        int errors,
        long buildDurationMillis,
        long testDurationMillis,
        FailureKind primaryFailureKind) implements Serializable {

    private static final long serialVersionUID = 1L;

    public static BenchmarkVerificationSummary from(VerificationResult verification) {
        VerificationResult safe = verification == null ? VerificationResult.empty() : verification;
        return new BenchmarkVerificationSummary(
                safe.build().exitCode(),
                safe.test().exitCode(),
                safe.build().passed(),
                safe.test().passed(),
                safe.test().testSummary().testsRun(),
                safe.test().testSummary().failures(),
                safe.test().testSummary().errors(),
                safe.build().durationMillis(),
                safe.test().durationMillis(),
                safe.primaryFailureKind());
    }

    public static BenchmarkVerificationSummary empty() {
        return from(VerificationResult.empty());
    }
}
