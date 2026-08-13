package com.core.multiAgentSoftwareStudio.Service.Repair;

import com.core.multiAgentSoftwareStudio.Config.RepairBudgetConfig;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 将 LLM 修复视为候选变更；若验证阶段退化到更早的失败层级，则恢复修复前快照。
 */
@Service
public class RepairRegressionGuardService {

    private final WorkspaceService workspaceService;
    private final SourceCodePathService sourceCodePathService;

    public RepairRegressionGuardService(
            WorkspaceService workspaceService,
            SourceCodePathService sourceCodePathService) {
        this.workspaceService = workspaceService;
        this.sourceCodePathService = sourceCodePathService;
    }

    /**
     * 检查候选修复是否把契约、测试或 Spring 失败降级为编译失败，并在需要时恢复内存与磁盘。
     */
    public boolean rollbackIfRegressed(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        if (data == null || data.repairBaselineCodes == null || data.repairBaselineCodes.isEmpty()
                || data.repairCandidateChangedFiles == null || data.repairCandidateChangedFiles.isEmpty()) {
            return false;
        }
        FailureKind baseline = safeFailure(data.repairBaselineFailureKind);
        FailureKind candidate = safeFailure(data.pendingFailureKind);
        if (candidate == FailureKind.ENVIRONMENT || candidate == FailureKind.UNKNOWN) {
            // 外部环境失败不能证明候选源码退化，避免网络或 Docker 波动触发错误回滚。
            clearCandidate(data);
            return false;
        }
        if (failureStage(candidate) >= failureStage(baseline)) {
            clearCandidate(data);
            return false;
        }

        Consumer<String> safeLogger = logger == null ? message -> { } : logger;
        safeLogger.accept("8. Repair candidate regressed from " + baseline + " to " + candidate
                + "; restoring the last verified source snapshot.");
        if (data.projectPath != null) {
            workspaceService.restoreRepairSnapshot(
                    Path.of(data.projectPath), data.repairBaselineCodes,
                    data.repairCandidateChangedFiles, safeLogger);
        }
        restoreCodes(data, data.repairBaselineCodes);
        data.validationWarnings = new ArrayList<>(data.repairBaselineValidationWarnings);
        data.qualityPolicyResult = data.repairBaselineQualityPolicyResult;
        data.verificationResult = data.repairBaselineVerificationResult;
        data.executionResult = data.repairBaselineExecutionResult == null ? "" : data.repairBaselineExecutionResult;
        data.testResult = data.repairBaselineTestResult == null ? "" : data.repairBaselineTestResult;
        data.pendingFailureKind = baseline;
        data.pendingErrorType = data.repairBaselineErrorType == null ? baseline.name() : data.repairBaselineErrorType;
        data.pendingFixLog = data.repairBaselineFixLog == null ? "" : data.repairBaselineFixLog;
        data.repairRollbackCount++;
        int baselineRollbackCount = recordBaselineRollback(data);
        // 回滚后重新面对的是尚未解决的原始问题，不属于普通的“同一候选连续无变化”。
        data.lastFailureFingerprint = null;
        data.repeatedFailureCount = 0;
        if (baselineRollbackCount >= RepairBudgetConfig.MAX_REGRESSION_ROLLBACKS_PER_FAILURE) {
            data.repairStopRequested = true;
            data.repeatedRegressionStopCount++;
            data.markRepairStopped("REPEATED_REGRESSION");
            safeLogger.accept("   The same baseline failure produced " + baselineRollbackCount
                    + " regressed repair candidates; stopping further LLM repairs for this run.");
        } else {
            safeLogger.accept("   Baseline failure rollback " + baselineRollbackCount + "/"
                    + RepairBudgetConfig.MAX_REGRESSION_ROLLBACKS_PER_FAILURE
                    + "; the remaining budget may try a different repair.");
        }
        clearCandidate(data);
        return true;
    }

    /**
     * 候选修复已经通过当前验证层级时提交它，不再保留旧快照。
     */
    public void commitCandidate(SoftwareStudioWorkflowData data) {
        if (data != null) {
            clearCandidate(data);
        }
    }

    /**
     * 候选修改没有带来真实工具进展时恢复本轮基线，保留此前已经验证过的最佳代码。
     */
    public void rollbackCandidateWithoutProgress(
            SoftwareStudioWorkflowData data,
            Consumer<String> logger,
            String reason) {
        if (data == null || data.repairBaselineCodes == null || data.repairBaselineCodes.isEmpty()) {
            return;
        }
        Consumer<String> safeLogger = logger == null ? message -> { } : logger;
        safeLogger.accept("   Repair candidate made no verified progress; restoring the best checkpoint."
                + (reason == null || reason.isBlank() ? "" : " " + reason));
        if (data.projectPath != null) {
            workspaceService.restoreRepairSnapshot(
                    Path.of(data.projectPath), data.repairBaselineCodes,
                    data.repairCandidateChangedFiles, safeLogger);
        }
        restoreCodes(data, data.repairBaselineCodes);
        data.validationWarnings = new ArrayList<>(data.repairBaselineValidationWarnings);
        data.qualityPolicyResult = data.repairBaselineQualityPolicyResult;
        data.verificationResult = data.repairBaselineVerificationResult;
        data.executionResult = data.repairBaselineExecutionResult == null ? "" : data.repairBaselineExecutionResult;
        data.testResult = data.repairBaselineTestResult == null ? "" : data.repairBaselineTestResult;
        data.pendingFailureKind = safeFailure(data.repairBaselineFailureKind);
        data.pendingErrorType = data.repairBaselineErrorType;
        data.pendingFixLog = data.repairBaselineFixLog;
        data.repairRollbackCount++;
        clearCandidate(data);
    }

    private void restoreCodes(SoftwareStudioWorkflowData data, Map<String, String> baseline) {
        data.codes = baseline.entrySet().stream()
                .map(entry -> new SourceCode(
                        entry.getKey(),
                        sourceCodePathService.detectLanguageFromFilename(entry.getKey()),
                        entry.getValue()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private FailureKind safeFailure(FailureKind kind) {
        return kind == null ? FailureKind.UNKNOWN : kind;
    }

    /**
     * 按基线失败指纹累计退化回滚次数，避免一次回滚触发普通重复失败，同时限制无效候选循环。
     */
    private int recordBaselineRollback(SoftwareStudioWorkflowData data) {
        String fingerprint = data.repairBaselineFailureFingerprint;
        if (fingerprint == null || fingerprint.isBlank()) {
            // 正常工作流会在分流后保存指纹；该兜底仅保护旧序列化状态或直接构造的测试状态。
            fingerprint = data.currentSliceId() + "|" + safeFailure(data.repairBaselineFailureKind)
                    + "|" + (data.repairBaselineFixLog == null ? "" : data.repairBaselineFixLog);
        }
        int count = data.regressionRollbackCountsByFailure.getOrDefault(fingerprint, 0) + 1;
        data.regressionRollbackCountsByFailure.put(fingerprint, count);
        return count;
    }

    /**
     * 数值越大表示流程走得越远；只在候选修复退回更早技术阶段时触发回滚。
     */
    private int failureStage(FailureKind kind) {
        return switch (kind) {
            case CONTRACT -> 6;
            case TEST_ASSERTION, TEST_DISCOVERY, TEST_CODE -> 5;
            case SPRING_CONTEXT, IMPLEMENTATION -> 4;
            case TEST_COMPILE -> 3;
            case MAIN_COMPILE -> 2;
            case BUILD_PROFILE, ARCHITECTURE -> 1;
            case NONE -> 7;
            case ENVIRONMENT, UNKNOWN -> 0;
        };
    }

    private void clearCandidate(SoftwareStudioWorkflowData data) {
        data.repairBaselineCodes.clear();
        data.repairCandidateChangedFiles.clear();
        data.repairBaselineValidationWarnings.clear();
        data.repairBaselineFailureKind = FailureKind.NONE;
        data.repairBaselineFixLog = null;
        data.repairBaselineErrorType = null;
        data.repairBaselineExecutionResult = null;
        data.repairBaselineTestResult = null;
        data.repairBaselineFailureFingerprint = null;
    }
}
