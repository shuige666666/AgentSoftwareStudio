package com.core.multiAgentSoftwareStudio.Model.Workflow;

/**
 * 描述候选修复相对当前最佳检查点的真实工具进展。
 */
public enum RepairProgress {
    PASSED,
    PROGRESSED,
    NO_PROGRESS,
    REGRESSED
}
