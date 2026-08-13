package com.core.multiAgentSoftwareStudio.Model.Workflow;

import java.io.Serializable;

/**
 * 聚合构建和测试阶段的验证证据，并提供工作流唯一可信的成功结论。
 */
public record VerificationResult(
        VerificationStepResult build,
        VerificationStepResult test,
        boolean passed,
        FailureKind primaryFailureKind) implements Serializable {

    private static final long serialVersionUID = 1L;

    public VerificationResult {
        build = build == null ? VerificationStepResult.notRun(VerificationStage.BUILD) : build;
        test = test == null ? VerificationStepResult.notRun(VerificationStage.TEST) : test;
        primaryFailureKind = primaryFailureKind == null ? FailureKind.UNKNOWN : primaryFailureKind;
    }

    public static VerificationResult empty() {
        return new VerificationResult(
                VerificationStepResult.notRun(VerificationStage.BUILD),
                VerificationStepResult.notRun(VerificationStage.TEST),
                false,
                FailureKind.NONE);
    }

    public static VerificationResult afterBuild(VerificationStepResult build) {
        FailureKind failureKind = build != null && !build.passed() ? build.failureKind() : FailureKind.NONE;
        return new VerificationResult(build, VerificationStepResult.notRun(VerificationStage.TEST), false, failureKind);
    }

    public VerificationResult withTest(VerificationStepResult testResult) {
        boolean allPassed = build.passed() && testResult != null && testResult.passed();
        FailureKind failureKind = allPassed
                ? FailureKind.NONE
                : !build.passed() ? build.failureKind() : testResult.failureKind();
        return new VerificationResult(build, testResult, allPassed, failureKind);
    }
}
