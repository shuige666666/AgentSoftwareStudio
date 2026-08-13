package com.core.multiAgentSoftwareStudio.Model.Benchmark;

import java.util.List;
import java.util.Map;

/**
 * 显式触发真实 LLM 基准运行的请求。空 caseIds 表示运行全部基准任务；
 * repetitions 控制每个案例顺序重复次数，人工版本标签用于区分同名滚动模型。
 */
public record BenchmarkRunRequest(
        List<String> caseIds,

        /**
         * 每个案例的重复次数。
         */
        Integer repetitions,

        /**
         * 人工模型版本标签，用于区分供应商同名滚动版本。
         */
        Map<String, String> modelReleaseLabels) {
}
