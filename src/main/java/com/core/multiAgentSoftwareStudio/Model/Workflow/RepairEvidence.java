package com.core.multiAgentSoftwareStudio.Model.Workflow;

import java.io.Serializable;

/**
 * 保存一轮编译、测试或契约验证的最小证据，用于判断是否值得继续调用修复模型。
 */
public record RepairEvidence(
        FailureKind failureKind,
        boolean passed,
        int stageScore,
        int remainingProblemCount,
        String command,
        Integer exitCode,
        String evidence) implements Serializable {

    private static final long serialVersionUID = 1L;

    public RepairEvidence {
        failureKind = failureKind == null ? FailureKind.UNKNOWN : failureKind;
        command = command == null ? "" : command;
        evidence = evidence == null ? "" : evidence;
        remainingProblemCount = Math.max(0, remainingProblemCount);
    }
}
