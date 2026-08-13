package com.core.multiAgentSoftwareStudio.Model.Workflow;

/**
 * 标识失败应回到的责任阶段，避免所有问题都交给同一个 Debugger Prompt。
 */
public enum RepairTarget {
    PROJECT_PROFILE,
    TESTS,
    IMPLEMENTATION,
    CONTRACT,
    ARCHITECTURE,
    ENVIRONMENT,
    STOP
}
