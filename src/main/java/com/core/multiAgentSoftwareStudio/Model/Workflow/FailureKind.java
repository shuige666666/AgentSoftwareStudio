package com.core.multiAgentSoftwareStudio.Model.Workflow;

/**
 * 对生成项目失败原因做稳定分类，供修复路由和基准聚合共同使用。
 */
public enum FailureKind {
    NONE,
    BUILD_PROFILE,
    MAIN_COMPILE,
    TEST_COMPILE,
    TEST_DISCOVERY,
    TEST_CODE,
    TEST_ASSERTION,
    SPRING_CONTEXT,
    CONTRACT,
    IMPLEMENTATION,
    ARCHITECTURE,
    ENVIRONMENT,
    UNKNOWN
}
