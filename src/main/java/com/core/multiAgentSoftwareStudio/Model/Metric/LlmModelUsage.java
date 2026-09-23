package com.core.multiAgentSoftwareStudio.Model.Metric;

/**
 * 按实际模型名保留调用与 Token 用量，供基准核对不同模型的费用。
 */
public record LlmModelUsage(long calls, long failedCalls, long inputCacheHitTokens,
                            long inputCacheMissTokens, long outputTokens, long totalTokens) {
}
