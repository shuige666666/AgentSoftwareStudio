package com.core.multiAgentSoftwareStudio.Model.Benchmark;

/**
 * 一项从生成项目外部观察到的需求行为。
 */
public record BenchmarkBehaviorFinding(String behavior, boolean passed, String evidence) {
}
