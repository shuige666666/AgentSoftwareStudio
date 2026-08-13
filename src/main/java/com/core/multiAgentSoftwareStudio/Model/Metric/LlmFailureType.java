package com.core.multiAgentSoftwareStudio.Model.Metric;

/**
 * 标记单次 LLM 调用失败的可聚合类型。
 */
public enum LlmFailureType {
    TIMEOUT,
    HTTP_ERROR,
    INTERRUPTED,
    RESPONSE_PARSE_ERROR,
    PROVIDER_ERROR,
    UNKNOWN
}
