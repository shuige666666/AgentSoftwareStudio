package com.core.multiAgentSoftwareStudio.Model.Workflow;

/**
 * 标识切片测试被过滤的稳定原因，供工作流统计而不保存具体测试源码。
 */
public enum TestScopeViolation {
    NONE,
    FUTURE_TYPE,
    FUTURE_RESOURCE
}
