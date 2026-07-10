package com.core.multiAgentSoftwareStudio.Service.Benchmark;

/**
 * 独立质量门禁给出的可追溯结论。
 */
public record BenchmarkFinding(
        BenchmarkGate gate,
        boolean passed,
        String evidence) {
}
