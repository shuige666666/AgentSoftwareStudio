package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityGateSeverity;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityPolicyFinding;
import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Repair.CodeFix;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectProfileService;
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

    public PreflightValidationNodeService(
            ProjectProfileService projectProfileService,
            WorkspaceService workspaceService,
            SourceCodePathService sourceCodePathService,
            RunJournalService runJournalService) {
        this.projectProfileService = projectProfileService;
        this.workspaceService = workspaceService;
        this.sourceCodePathService = sourceCodePathService;
        this.runJournalService = runJournalService;
    }

    /**
     * 先执行安全归一化，再运行 Profile 和契约门禁；BLOCK 项不会进入沙箱。
     */
    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        logger.accept("6. Applying project profile and deterministic preflight gates.");
        data.projectProfile = projectProfileService.resolve(data.structure);
        List<String> normalizedFiles = projectProfileService.applySafeDefaults(data.projectProfile, data.codes);
        syncNormalizedFiles(data, normalizedFiles, logger);

        ProjectQualityPolicyResult result = projectProfileService.evaluate(
                data.projectProfile, data.codes, data.contract, normalizedFiles);
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

        FailureKind primaryFailure = technicalBlockers.getFirst().failureKind() == null
                ? FailureKind.UNKNOWN
                : technicalBlockers.getFirst().failureKind();
        data.pendingFailureKind = primaryFailure;
        data.pendingErrorType = primaryFailure.name();
        data.pendingFixLog = technicalBlockers.stream()
                .map(finding -> finding.gate() + ": " + finding.evidence())
                .reduce((left, right) -> left + "\n" + right)
                .orElse("Preflight validation failed");
        data.shouldFix = !data.repairStopRequested && data.currentAttempt < data.maxRetries;
        if (!data.shouldFix) {
            logger.accept("   Preflight failed and the shared repair budget is exhausted.");
        }
        return data;
    }

    /**
     * 已落盘项目再次经过 Profile 归一化时，只同步实际变化的文件，避免创建新项目目录。
     */
    private void syncNormalizedFiles(
            SoftwareStudioWorkflowData data,
            List<String> normalizedFiles,
            Consumer<String> logger) {
        if (data.projectPath == null || normalizedFiles == null || normalizedFiles.isEmpty()) {
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
            }
        }
        if (!fixes.isEmpty()) {
            workspaceService.applyFixesToDisk(Path.of(data.projectPath), fixes, logger);
        }
    }
}
