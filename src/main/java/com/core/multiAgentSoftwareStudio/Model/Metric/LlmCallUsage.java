package com.core.multiAgentSoftwareStudio.Model.Metric;

/**
 * 记录单次 LLM 调用的 Token 用量；只有供应商返回可识别字段时才标记缓存指标可用。
 */
public record LlmCallUsage(
        long inputCacheHitTokens,
        long inputCacheMissTokens,
        long outputTokens,
        long totalTokens,
        boolean cacheMetricsAvailable) {

    public long inputTokens() {
        return inputCacheHitTokens + inputCacheMissTokens;
    }
}
