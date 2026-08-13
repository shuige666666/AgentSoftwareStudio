package com.core.multiAgentSoftwareStudio.Model.Metric;

/**
 * 汇总成功 LLM 调用的结束原因，用于判断输出是否被长度或安全策略截断。
 */
public record LlmFinishReasonCounts(
        long stop,
        long length,
        long contentFilter,
        long toolCalls,
        long other) {

    public static LlmFinishReasonCounts empty() {
        return new LlmFinishReasonCounts(0, 0, 0, 0, 0);
    }

    public long total() {
        return stop + length + contentFilter + toolCalls + other;
    }
}
