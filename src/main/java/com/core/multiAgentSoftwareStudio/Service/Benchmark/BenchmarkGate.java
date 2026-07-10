package com.core.multiAgentSoftwareStudio.Service.Benchmark;

/**
 * 基准任务的独立质量门禁。门禁不复用工作流的 success 判断，避免同一套逻辑自证正确。
 */
public enum BenchmarkGate {
    NO_PLACEHOLDER_SOURCE,
    HAS_TEST_SOURCE,
    TESTS_EXECUTED_AND_GREEN,
    HAS_SPRING_CONTEXT_TEST
}
