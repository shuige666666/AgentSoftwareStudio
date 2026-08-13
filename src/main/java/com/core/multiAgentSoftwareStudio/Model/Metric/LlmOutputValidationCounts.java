package com.core.multiAgentSoftwareStudio.Model.Metric;

/**
 * 汇总模型成功返回后的内容校验失败，避免将业务 JSON 问题误计为 HTTP 失败。
 */
public record LlmOutputValidationCounts(
        long emptyContent,
        long jsonParseError,
        long schemaValidationError) {

    public static LlmOutputValidationCounts empty() {
        return new LlmOutputValidationCounts(0, 0, 0);
    }

    public long total() {
        return emptyContent + jsonParseError + schemaValidationError;
    }
}
