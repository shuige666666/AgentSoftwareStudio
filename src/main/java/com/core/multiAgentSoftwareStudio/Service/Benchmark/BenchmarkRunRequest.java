package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import java.util.List;

/**
 * 显式触发真实 LLM 基准运行的请求。空 caseIds 表示运行全部基准任务。
 */
public record BenchmarkRunRequest(List<String> caseIds, Integer maxRetries) {
}
