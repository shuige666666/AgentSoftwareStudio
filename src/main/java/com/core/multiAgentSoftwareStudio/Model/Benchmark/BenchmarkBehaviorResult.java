package com.core.multiAgentSoftwareStudio.Model.Benchmark;

import java.util.List;

/**
 * 保存独立行为判卷的状态；环境异常不能被算作业务通过或业务失败。
 */
public record BenchmarkBehaviorResult(String status, List<BenchmarkBehaviorFinding> findings, String reason) {
    public BenchmarkBehaviorResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
    }

    public static BenchmarkBehaviorResult notApplicable() {
        return new BenchmarkBehaviorResult("NOT_APPLICABLE", List.of(), null);
    }

    public static BenchmarkBehaviorResult notRun(String reason) {
        return new BenchmarkBehaviorResult("NOT_RUN", List.of(), reason);
    }

    public static BenchmarkBehaviorResult environmentError(String reason) {
        return new BenchmarkBehaviorResult("ENVIRONMENT_ERROR", List.of(), reason);
    }

    public static BenchmarkBehaviorResult fromFindings(List<BenchmarkBehaviorFinding> findings) {
        return new BenchmarkBehaviorResult(
                findings.stream().allMatch(BenchmarkBehaviorFinding::passed) ? "PASS" : "FAIL",
                findings, null);
    }

    public boolean passed() {
        return "PASS".equals(status);
    }
}
