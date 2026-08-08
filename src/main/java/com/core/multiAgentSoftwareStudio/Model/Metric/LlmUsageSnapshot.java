package com.core.multiAgentSoftwareStudio.Model.Metric;

/**
 * 保存一次生成任务或基准用例的 LLM 聚合用量快照。
 */
public record LlmUsageSnapshot(
        long calls,
        long successfulCalls,
        long failedCalls,
        long durationMillis,
        long inputCacheHitTokens,
        long inputCacheMissTokens,
        long outputTokens,
        long totalTokens,
        boolean cacheMetricsAvailable,
        LlmFinishReasonCounts finishReasonCounts,
        LlmFailureCounts failureCounts,
        LlmOutputValidationCounts outputValidationCounts) {

    public LlmUsageSnapshot {
        finishReasonCounts = finishReasonCounts == null ? LlmFinishReasonCounts.empty() : finishReasonCounts;
        failureCounts = failureCounts == null ? LlmFailureCounts.empty() : failureCounts;
        outputValidationCounts = outputValidationCounts == null
                ? LlmOutputValidationCounts.empty()
                : outputValidationCounts;
    }

    /**
     * 保留原有构造方式，便于旧测试和空快照使用默认计数。
     */
    public LlmUsageSnapshot(long calls,
                            long successfulCalls,
                            long failedCalls,
                            long durationMillis,
                            long inputCacheHitTokens,
                            long inputCacheMissTokens,
                            long outputTokens,
                            long totalTokens,
                            boolean cacheMetricsAvailable) {
        this(calls, successfulCalls, failedCalls, durationMillis,
                inputCacheHitTokens, inputCacheMissTokens, outputTokens, totalTokens,
                cacheMetricsAvailable, LlmFinishReasonCounts.empty(), LlmFailureCounts.empty(),
                LlmOutputValidationCounts.empty());
    }

    public long inputTokens() {
        return inputCacheHitTokens + inputCacheMissTokens;
    }

    /**
     * 计算远程输入缓存命中率；当前供应商不提供该指标时返回 null。
     */
    public Double remoteInputCacheHitRate() {
        if (!cacheMetricsAvailable) {
            return null;
        }
        long inputTokens = inputTokens();
        return inputTokens == 0 ? 0.0 : (double) inputCacheHitTokens / inputTokens;
    }
}
