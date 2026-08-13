package com.core.multiAgentSoftwareStudio.Model.Metric;

/**
 * 标记模型输出在 Agent 业务解析阶段的校验失败类型。
 */
public enum LlmOutputValidationType {
    EMPTY_CONTENT,
    JSON_PARSE_ERROR,
    SCHEMA_VALIDATION_ERROR
}
