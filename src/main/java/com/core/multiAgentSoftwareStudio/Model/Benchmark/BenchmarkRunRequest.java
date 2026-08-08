package com.core.multiAgentSoftwareStudio.Model.Benchmark;

import java.util.List;
import java.util.Map;

/**
 * 显式触发真实 LLM 基准运行的请求。空 caseIds 表示运行全部基准任务；人工版本标签用于区分同名滚动模型。
 */
public record BenchmarkRunRequest(
        List<String> caseIds,
        Integer maxRetries,
        Map<String, String> modelReleaseLabels) {
}
