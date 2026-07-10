package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import java.util.Set;

/**
 * 一条可重复运行的真实 LLM 生成任务。
 */
public record BenchmarkCase(
        String id,
        String title,
        String prompt,
        Set<BenchmarkGate> requiredGates) {
}
