package com.core.multiAgentSoftwareStudio.Model.Metric;

/**
 * 记录单次 LLM 调用的 Token 用量，区分远程缓存命中与未命中的输入。
 */
public record LlmCallUsage(
        long inputCacheHitTokens,
        long inputCacheMissTokens,
        long outputTokens,
        long totalTokens) {
}
