package com.core.multiAgentSoftwareStudio.Model.Workflow;

import java.io.Serializable;

/**
 * 表示构建、测试或前置门禁中一个阶段的结构化验证结论。
 */
public record VerificationStepResult(
        VerificationStage stage,
        VerificationStatus status,
        FailureKind failureKind,
        String command,
        Integer exitCode,
        long durationMillis,
        TestSummary testSummary,
        String evidence) implements Serializable {

    private static final long serialVersionUID = 1L;

    public VerificationStepResult {
        stage = stage == null ? VerificationStage.NOT_RUN : stage;
        status = status == null ? VerificationStatus.NOT_RUN : status;
        failureKind = failureKind == null ? FailureKind.UNKNOWN : failureKind;
        command = command == null ? "" : command;
        testSummary = testSummary == null ? TestSummary.empty() : testSummary;
        evidence = evidence == null ? "" : evidence;
    }

    public static VerificationStepResult notRun(VerificationStage stage) {
        return new VerificationStepResult(stage, VerificationStatus.NOT_RUN, FailureKind.NONE,
                "", null, 0, TestSummary.empty(), "");
    }

    public boolean passed() {
        return status == VerificationStatus.PASSED;
    }
}
