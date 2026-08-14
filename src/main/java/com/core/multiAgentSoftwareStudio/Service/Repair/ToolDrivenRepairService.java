package com.core.multiAgentSoftwareStudio.Service.Repair;

import com.core.multiAgentSoftwareStudio.Model.Generation.QualityPolicyFinding;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairEvidence;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairProgress;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStage;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStepResult;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectProfileService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workflow.VerificationResultService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.PersistenceNodeService;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * 在完整项目上运行真实编译和测试，并用最新工具证据驱动最多三加一轮连续修复。
 */
@Service
public class ToolDrivenRepairService {

    private static final int DEFAULT_REPAIR_ROUNDS = 3;
    private static final int PROGRESS_EXTENSION_ROUNDS = 1;
    private static final Pattern COMPILER_PROBLEM = Pattern.compile(
            "(?m)^\\[ERROR].*(?:\\.java:|cannot find symbol|COMPILATION ERROR)");

    private final DockerSandboxService sandboxService;
    private final VerificationResultService verificationResultService;
    private final ProjectProfileService projectProfileService;
    private final PersistenceNodeService persistenceNodeService;
    private final WorkspaceRepairService workspaceRepairService;
    private final RepairProgressEvaluator progressEvaluator;
    private final RepairRegressionGuardService regressionGuardService;
    private final RunJournalService runJournalService;

    public ToolDrivenRepairService(
            DockerSandboxService sandboxService,
            VerificationResultService verificationResultService,
            ProjectProfileService projectProfileService,
            PersistenceNodeService persistenceNodeService,
            WorkspaceRepairService workspaceRepairService,
            RepairProgressEvaluator progressEvaluator,
            RepairRegressionGuardService regressionGuardService,
            RunJournalService runJournalService) {
        this.sandboxService = sandboxService;
        this.verificationResultService = verificationResultService;
        this.projectProfileService = projectProfileService;
        this.persistenceNodeService = persistenceNodeService;
        this.workspaceRepairService = workspaceRepairService;
        this.progressEvaluator = progressEvaluator;
        this.regressionGuardService = regressionGuardService;
        this.runJournalService = runJournalService;
    }

    /**
     * 先应用无歧义工程归一化，再用 Java 17 沙箱编译完整生产源码并连续修复。
     */
    public boolean compileAndRepair(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        prepareCompleteProject(data, logger);
        logger.accept("7. Starting tool-driven production compile session.");
        return runRepairSession(data, logger, false);
    }

    /**
     * 编译已经绿色后运行完整测试和最终契约检查；候选修复每轮都会重新编译再测试。
     */
    public boolean testAndRepair(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        prepareCompleteProject(data, logger);
        resetSessionStopState(data);
        logger.accept("8. Starting tool-driven test and contract session.");
        boolean passed = runRepairSession(data, logger, true);
        data.success = passed;
        return passed;
    }

    private void prepareCompleteProject(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        data.finalVerificationStarted = true;
        data.projectProfile = projectProfileService.resolve(data.structure);
        List<String> normalized = projectProfileService.applySafeDefaults(data.projectProfile, data.codes);
        if (!normalized.isEmpty()) {
            logger.accept("   Deterministic normalization updated " + normalized.size() + " file(s).");
        }
        persistenceNodeService.persistWholeProject(data, logger);
    }

    private boolean runRepairSession(
            SoftwareStudioWorkflowData data,
            Consumer<String> logger,
            boolean includeTestsAndContract) {
        RepairEvidence best = verify(data, logger, includeTestsAndContract);
        if (best.passed()) {
            return true;
        }
        int maximumRounds = DEFAULT_REPAIR_ROUNDS;
        for (int round = 1; round <= maximumRounds; round++) {
            if (best.failureKind() == FailureKind.ENVIRONMENT
                    || best.failureKind() == FailureKind.ARCHITECTURE) {
                stop(data, best.failureKind().name());
                return false;
            }
            if (!data.canRepairNow()) {
                data.markRepairBudgetUnavailable();
                return false;
            }

            logger.accept("   Tool repair round " + round + "/" + maximumRounds
                    + ": failure=" + best.failureKind()
                    + ", remaining=" + best.remainingProblemCount() + ".");
            setPendingFailure(data, best);
            data.shouldFix = true;
            workspaceRepairService.repair(data, logger);
            if (data.repairStopRequested || data.repairCandidateChangedFiles.isEmpty()) {
                return false;
            }

            RepairEvidence candidate = verify(data, logger, includeTestsAndContract);
            RepairProgress progress = progressEvaluator.compare(best, candidate);
            logger.accept("   Verified repair result: " + progress
                    + ", failure=" + candidate.failureKind()
                    + ", remaining=" + candidate.remainingProblemCount() + ".");

            if (progress == RepairProgress.PASSED) {
                regressionGuardService.commitCandidate(data);
                clearPendingFailure(data);
                return true;
            }
            if (progress == RepairProgress.PROGRESSED) {
                regressionGuardService.commitCandidate(data);
                best = candidate;
                // 第三轮仍由真实工具确认有进展时，才开放唯一的第四轮。
                if (round == DEFAULT_REPAIR_ROUNDS) {
                    maximumRounds = DEFAULT_REPAIR_ROUNDS + PROGRESS_EXTENSION_ROUNDS;
                    logger.accept("   Round 3 made verified progress; extending the session to round 4.");
                }
                continue;
            }

            regressionGuardService.rollbackCandidateWithoutProgress(
                    data, logger, "Result=" + progress + ".");
            stop(data, progress == RepairProgress.REGRESSED ? "REGRESSION" : "NO_PROGRESS");
            return false;
        }
        stop(data, "ROUND_LIMIT");
        return false;
    }

    /**
     * 测试阶段的每个候选先重新编译，避免通过改测试掩盖生产代码退化。
     */
    private RepairEvidence verify(
            SoftwareStudioWorkflowData data,
            Consumer<String> logger,
            boolean includeTestsAndContract) {
        String projectType = data.projectProfile == null
                ? data.structure.projectType() : data.projectProfile.projectType();
        SandboxExecutionResult compileExecution = sandboxService.runCompileInSandboxWithResult(
                Path.of(data.projectPath), projectType);
        VerificationStepResult build = verificationResultService.toStep(VerificationStage.BUILD, compileExecution);
        data.executionResult = compileExecution.output();
        data.verificationResult = VerificationResult.afterBuild(build);
        runJournalService.recordVerification(data, build);
        if (!build.passed()) {
            logger.accept("   Compile failed with exit code " + compileExecution.exitCode() + ".");
            return evidence(build.failureKind(), false, compileExecution, build.evidence(),
                    countCompilerProblems(compileExecution.output()));
        }
        if (!includeTestsAndContract) {
            logger.accept("   Production compile passed.");
            return evidence(FailureKind.NONE, true, compileExecution, build.evidence(), 0);
        }

        SandboxExecutionResult testExecution = sandboxService.runTestsInSandboxWithResult(
                Path.of(data.projectPath), projectType, List.of());
        VerificationStepResult test = verificationResultService.toStep(VerificationStage.TEST, testExecution);
        data.testResult = testExecution.output();
        data.verificationResult = data.verificationResult.withTest(test);
        runJournalService.recordVerification(data, test);
        if (!test.passed()) {
            logger.accept("   Tests failed with exit code " + testExecution.exitCode() + ".");
            // 测试源码编译失败同样按编译器问题数判断进展，不能因为没有 Surefire 摘要就恒定为 1。
            int remaining = test.failureKind() == FailureKind.TEST_COMPILE
                    ? countCompilerProblems(testExecution.output())
                    : Math.max(1, test.testSummary().failures() + test.testSummary().errors());
            return evidence(test.failureKind(), false, testExecution, test.evidence(), remaining);
        }

        data.qualityPolicyResult = projectProfileService.evaluate(
                data.projectProfile, data.codes, data.contract, List.of());
        List<QualityPolicyFinding> blockers = data.qualityPolicyResult.blockingFindings();
        if (!blockers.isEmpty()) {
            FailureKind kind = blockers.stream()
                    .map(QualityPolicyFinding::failureKind)
                    .filter(java.util.Objects::nonNull)
                    .findFirst().orElse(FailureKind.CONTRACT);
            String evidence = blockers.stream()
                    .map(finding -> finding.gate() + ": " + finding.evidence())
                    .reduce((left, right) -> left + "\n" + right).orElse("Contract validation failed");
            return new RepairEvidence(kind, false, progressEvaluator.stageScore(kind), blockers.size(),
                    "deterministic final contract validation", 1, evidence);
        }

        logger.accept("   Full tests and final contract validation passed.");
        return evidence(FailureKind.NONE, true, testExecution, test.evidence(), 0);
    }

    private RepairEvidence evidence(
            FailureKind kind,
            boolean passed,
            SandboxExecutionResult execution,
            String summary,
            int remaining) {
        String fullEvidence = execution.output() == null || execution.output().isBlank()
                ? summary : execution.output();
        return new RepairEvidence(kind, passed, progressEvaluator.stageScore(kind), remaining,
                execution.command(), execution.exitCode(), fullEvidence);
    }

    private int countCompilerProblems(String output) {
        if (output == null || output.isBlank()) {
            return 1;
        }
        int count = (int) COMPILER_PROBLEM.matcher(output).results().count();
        return Math.max(1, count);
    }

    private void setPendingFailure(SoftwareStudioWorkflowData data, RepairEvidence evidence) {
        data.pendingFailureKind = evidence.failureKind();
        data.pendingErrorType = evidence.failureKind().name();
        data.pendingFixLog = "=== REAL TOOL COMMAND ===\n" + evidence.command()
                + "\n=== EXIT CODE ===\n" + evidence.exitCode()
                + "\n=== CURRENT REMAINING FAILURE ===\n" + evidence.evidence();
    }

    private void clearPendingFailure(SoftwareStudioWorkflowData data) {
        data.pendingFailureKind = FailureKind.NONE;
        data.pendingErrorType = null;
        data.pendingFixLog = null;
        data.shouldFix = false;
    }

    private void resetSessionStopState(SoftwareStudioWorkflowData data) {
        data.repairStopRequested = false;
        data.lastFailureFingerprint = null;
        data.repeatedFailureCount = 0;
    }

    private void stop(SoftwareStudioWorkflowData data, String reason) {
        data.repairStopRequested = true;
        data.shouldFix = false;
        data.markRepairStopped(reason);
    }
}
