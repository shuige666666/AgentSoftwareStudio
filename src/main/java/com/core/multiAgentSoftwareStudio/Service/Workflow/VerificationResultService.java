package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.TestSummary;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStage;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStatus;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStepResult;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 将沙箱命令的退出码和日志转换为稳定的验证结果与失败分类。
 */
@Service
public class VerificationResultService {

    private static final Pattern TEST_SUMMARY = Pattern.compile(
            "Tests run:\\s*(\\d+)\\s*,\\s*Failures:\\s*(\\d+)\\s*,\\s*Errors:\\s*(\\d+)\\s*,\\s*Skipped:\\s*(\\d+)",
            Pattern.CASE_INSENSITIVE);
    private static final int EVIDENCE_LIMIT = 1200;

    /**
     * 将一次沙箱执行转换为指定阶段的结构化结果；测试阶段必须真实执行至少一个测试。
     */
    public VerificationStepResult toStep(VerificationStage stage, SandboxExecutionResult execution) {
        SandboxExecutionResult safeExecution = execution == null
                ? new SandboxExecutionResult("", null, "", 0, false, "Missing sandbox result")
                : execution;
        TestSummary testSummary = stage == VerificationStage.TEST
                ? parseTestSummary(safeExecution.output())
                : TestSummary.empty();

        boolean commandPassed = safeExecution.completedSuccessfully();
        boolean passed = stage == VerificationStage.TEST
                ? commandPassed && testSummary.executedAndGreen()
                : commandPassed;
        FailureKind failureKind = passed
                ? FailureKind.NONE
                : classifyFailure(stage, safeExecution, testSummary);

        return new VerificationStepResult(
                stage,
                passed ? VerificationStatus.PASSED : VerificationStatus.FAILED,
                failureKind,
                safeExecution.command(),
                safeExecution.exitCode(),
                safeExecution.durationMillis(),
                testSummary,
                summarize(safeExecution.infrastructureError(), safeExecution.output()));
    }

    /**
     * 读取 Maven 输出最后一个测试总计，避免把逐类统计和 Surefire 最终总计重复相加。
     */
    public TestSummary parseTestSummary(String output) {
        String safeOutput = output == null ? "" : output;
        Matcher matcher = TEST_SUMMARY.matcher(safeOutput);
        int testsRun = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        boolean found = false;
        while (matcher.find()) {
            found = true;
            testsRun = Integer.parseInt(matcher.group(1));
            failures = Integer.parseInt(matcher.group(2));
            errors = Integer.parseInt(matcher.group(3));
            skipped = Integer.parseInt(matcher.group(4));
        }
        return new TestSummary(testsRun, failures, errors, skipped, found);
    }

    private FailureKind classifyFailure(VerificationStage stage,
            SandboxExecutionResult execution,
            TestSummary testSummary) {
        String output = (execution.infrastructureError() == null ? "" : execution.infrastructureError() + "\n")
                + execution.output();
        String lower = output.toLowerCase(Locale.ROOT);

        if (execution.timedOut() || execution.infrastructureError() != null
                || lower.contains("docker execution error") || lower.contains("cannot connect to the docker daemon")) {
            return FailureKind.ENVIRONMENT;
        }
        if (lower.contains("some problems were encountered while processing the poms")
                || lower.contains("projectbuildingexception")
                || lower.contains("dependencies.dependency.version")
                || lower.contains("non-resolvable parent pom")) {
            return FailureKind.BUILD_PROFILE;
        }
        if (stage == VerificationStage.TEST && !testSummary.summaryPresent()
                && execution.exitCode() != null && execution.exitCode() == 0) {
            return FailureKind.TEST_DISCOVERY;
        }
        if (lower.contains("failed to load applicationcontext") || lower.contains("beancreationexception")
                || lower.contains("unsatisfieddependencyexception")) {
            return FailureKind.SPRING_CONTEXT;
        }
        if (lower.contains("org.mockito.exceptions")
                || lower.contains("invalid use of argument matchers")
                || lower.contains("only void methods can donothing")
                || lower.contains("unnecessary stubbings detected")) {
            return FailureKind.TEST_CODE;
        }
        // Maven 正常测试日志也会出现 testCompile 生命周期；已有失败摘要时应优先按真实测试结果分类。
        if (testSummary.failures() > 0 || testSummary.errors() > 0) {
            return FailureKind.TEST_ASSERTION;
        }
        if (lower.contains("testcompile") || lower.contains("src/test/java")) {
            return FailureKind.TEST_COMPILE;
        }
        if (lower.contains("compilation error") || lower.contains("compilation failure")
                || lower.contains("maven-compiler-plugin") || lower.contains("cannot find symbol")) {
            return stage == VerificationStage.TEST ? FailureKind.TEST_COMPILE : FailureKind.MAIN_COMPILE;
        }
        if (lower.contains("there are test failures")) {
            return FailureKind.TEST_ASSERTION;
        }
        return FailureKind.UNKNOWN;
    }

    private String summarize(String infrastructureError, String output) {
        String combined = infrastructureError == null || infrastructureError.isBlank()
                ? output
                : infrastructureError + "\n" + output;
        String normalized = combined == null ? "" : combined.replaceAll("\\s+", " ").trim();
        return normalized.length() <= EVIDENCE_LIMIT
                ? normalized
                : normalized.substring(0, EVIDENCE_LIMIT) + "...";
    }
}
