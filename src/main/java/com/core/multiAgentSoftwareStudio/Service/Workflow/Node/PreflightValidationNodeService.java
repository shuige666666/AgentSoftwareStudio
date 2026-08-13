package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityGateSeverity;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityPolicyFinding;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFix;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectProfileService;
import com.core.multiAgentSoftwareStudio.Service.Generation.SliceProductionScopeService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 在进入 Docker 前应用固定 ProjectProfile，并把确定性问题作为硬门禁阻断。
 */
@Service
public class PreflightValidationNodeService {

    private final ProjectProfileService projectProfileService;
    private final WorkspaceService workspaceService;
    private final SourceCodePathService sourceCodePathService;
    private final RunJournalService runJournalService;
    private final SliceProductionScopeService sliceProductionScopeService;

    public PreflightValidationNodeService(
            ProjectProfileService projectProfileService,
            WorkspaceService workspaceService,
            SourceCodePathService sourceCodePathService,
            RunJournalService runJournalService,
            SliceProductionScopeService sliceProductionScopeService) {
        this.projectProfileService = projectProfileService;
        this.workspaceService = workspaceService;
        this.sourceCodePathService = sourceCodePathService;
        this.runJournalService = runJournalService;
        this.sliceProductionScopeService = sliceProductionScopeService;
    }

    /**
     * 先执行安全归一化，再运行 Profile 和契约门禁；BLOCK 项不会进入沙箱。
     */
    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        logger.accept("6. Applying project profile and deterministic preflight gates.");
        data.projectProfile = projectProfileService.resolve(data.structure);
        List<String> normalizedFiles = projectProfileService.applySafeDefaults(data.projectProfile, data.codes);
        syncNormalizedFiles(data, normalizedFiles, logger);

        var activeContract = data.currentSlice() == null || data.finalVerificationStarted
                ? data.contract
                : data.currentSlice().contract();
        ProjectQualityPolicyResult result = projectProfileService.evaluate(
                data.projectProfile, data.codes, activeContract, normalizedFiles);
        result = applySliceProductionScopeGate(data, result);
        result = applySliceTestDiscoveryGate(data, result);
        data.qualityPolicyResult = result;
        runJournalService.recordPreflight(data, result);
        // Profile/结构错误仍在 Docker 前阻断；语义契约错误延后到技术验证通过后处理，
        // 防止有限修复预算全部消耗在契约阶段才暴露编译或测试错误。
        List<QualityPolicyFinding> technicalBlockers = result.technicalBlockingFindings();
        data.preflightPassed = technicalBlockers.isEmpty();
        data.shouldFix = false;

        for (QualityPolicyFinding finding : result.findings()) {
            String message = finding.gate() + ": " + finding.evidence();
            logger.accept("   [" + finding.severity() + "] " + message);
            if (!data.validationWarnings.contains(message)) {
                data.validationWarnings.add(message);
            }
        }

        if (result.passed()) {
            logger.accept("   Deterministic preflight gates passed.");
            data.pendingFailureKind = FailureKind.NONE;
            data.pendingErrorType = null;
            data.pendingFixLog = null;
            return data;
        }

        if (technicalBlockers.isEmpty()) {
            logger.accept("   Semantic contract blockers recorded; technical verification runs before contract repair.");
            data.pendingFailureKind = FailureKind.NONE;
            data.pendingErrorType = null;
            data.pendingFixLog = null;
            return data;
        }

        FailureKind primaryFailure = technicalBlockers.stream()
                .map(finding -> finding.failureKind() == null ? FailureKind.UNKNOWN : finding.failureKind())
                .min(java.util.Comparator.comparingInt(this::failurePriority))
                .orElse(FailureKind.UNKNOWN);
        data.pendingFailureKind = primaryFailure;
        data.pendingErrorType = primaryFailure.name();
        data.pendingFixLog = technicalBlockers.stream()
                .map(finding -> finding.gate() + ": " + finding.evidence())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("Preflight validation failed");
        data.shouldFix = data.canRepairNow();
        if (!data.shouldFix) {
            data.markRepairBudgetUnavailable();
            logger.accept("   Preflight failed and the shared repair budget is exhausted.");
        }
        return data;
    }

    /**
     * 当前切片引用未来类型时在 Docker 前阻断，并把问题稳定路由到生产实现修复。
     */
    private ProjectQualityPolicyResult applySliceProductionScopeGate(
            SoftwareStudioWorkflowData data,
            ProjectQualityPolicyResult result) {
        List<String> references = sliceProductionScopeService.findFutureTypeReferences(data);
        if (references.isEmpty()) {
            return result;
        }
        List<QualityPolicyFinding> findings = new ArrayList<>();
        findings.add(new QualityPolicyFinding(
                "SLICE_FUTURE_TYPE_REFERENCE",
                QualityGateSeverity.BLOCK,
                FailureKind.MAIN_COMPILE,
                String.join("; ", references)));
        // 测试生成因生产越界被主动跳过时，测试缺失只是派生现象，不能抢占真实根因。
        result.findings().stream()
                .filter(finding -> !"PROFILE_TEST_SOURCE".equals(finding.gate()))
                .filter(finding -> !"PROFILE_SPRING_CONTEXT_TEST".equals(finding.gate()))
                .forEach(findings::add);
        return new ProjectQualityPolicyResult(false, findings, result.normalizedFiles());
    }

    /**
     * 每个切片必须拥有自己的聚焦验收测试，不能只依赖之前切片的绿色回归结果被误接受。
     */
    private ProjectQualityPolicyResult applySliceTestDiscoveryGate(
            SoftwareStudioWorkflowData data,
            ProjectQualityPolicyResult result) {
        if (data.finalVerificationStarted || data.currentSlice() == null || !data.currentSliceTestFiles.isEmpty()) {
            return result;
        }
        if (result.findings().stream().anyMatch(finding -> "SLICE_FUTURE_TYPE_REFERENCE".equals(finding.gate()))) {
            return result;
        }
        List<QualityPolicyFinding> findings = new ArrayList<>(result.findings());
        findings.add(new QualityPolicyFinding(
                "SLICE_TEST_DISCOVERY",
                QualityGateSeverity.BLOCK,
                FailureKind.TEST_DISCOVERY,
                "Current vertical slice did not produce a focused test file."));
        return new ProjectQualityPolicyResult(false, findings, result.normalizedFiles());
    }

    /**
     * 生产构建根因优先于测试缺失和语义契约，确保有限预算先修复可编译性。
     */
    private int failurePriority(FailureKind failureKind) {
        return switch (failureKind) {
            case BUILD_PROFILE -> 0;
            case MAIN_COMPILE, IMPLEMENTATION, SPRING_CONTEXT -> 1;
            case TEST_COMPILE, TEST_CODE -> 2;
            case TEST_DISCOVERY, TEST_ASSERTION -> 3;
            case CONTRACT -> 4;
            default -> 5;
        };
    }

    /**
     * 已落盘项目再次经过 Profile 归一化时，只同步实际变化的文件，避免创建新项目目录。
     */
    private void syncNormalizedFiles(
            SoftwareStudioWorkflowData data,
            List<String> normalizedFiles,
            Consumer<String> logger) {
        if (normalizedFiles == null || normalizedFiles.isEmpty()) {
            return;
        }
        List<CodeFix> fixes = new ArrayList<>();
        for (String filename : normalizedFiles) {
            SourceCode source = data.codes.stream()
                    .filter(candidate -> candidate != null)
                    .filter(candidate -> sourceCodePathService.normalizePath(candidate.filename()).equals(filename))
                    .findFirst()
                    .orElse(null);
            if (source != null) {
                fixes.add(new CodeFix(filename, "ProjectProfile 兼容性归一化", source.code()));
                if (!data.finalVerificationStarted && data.currentSlice() != null
                        && filename.startsWith("src/test/")
                        && !data.currentSliceTestFiles.contains(filename)) {
                    // 确定性新增的 Context 测试属于当前切片，必须进入本轮验证并在验收后成为回归基线。
                    data.currentSliceTestFiles.add(filename);
                }
            }
        }
        if (data.projectPath != null && !fixes.isEmpty()) {
            workspaceService.applyFixesToDisk(Path.of(data.projectPath), fixes, logger);
        }
    }
}
