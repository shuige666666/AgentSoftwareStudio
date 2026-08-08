package com.core.multiAgentSoftwareStudio.Model.Metric;

/**
 * 按传输和供应商错误类型汇总 LLM 调用失败，不保存单次失败明细。
 */
public record LlmFailureCounts(
        long timeout,
        long httpError,
        long interrupted,
        long responseParseError,
        long providerError,
        long unknown) {

    public static LlmFailureCounts empty() {
        return new LlmFailureCounts(0, 0, 0, 0, 0, 0);
    }

    public long total() {
        return timeout + httpError + interrupted + responseParseError + providerError + unknown;
    }
}
