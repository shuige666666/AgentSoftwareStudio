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
        long totalTokens) {

    /**
     * 计算远程输入缓存命中率，无输入 Token 时返回 0。
     */
    public double remoteInputCacheHitRate() {
        long inputTokens = inputCacheHitTokens + inputCacheMissTokens;
        return inputTokens == 0 ? 0.0 : (double) inputCacheHitTokens / inputTokens;
    }
}
