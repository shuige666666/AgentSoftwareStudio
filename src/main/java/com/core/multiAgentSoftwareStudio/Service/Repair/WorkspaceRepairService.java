package com.core.multiAgentSoftwareStudio.Service.Repair;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairDecision;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairTarget;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationResult;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentMode;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRuntime;
import com.core.multiAgentSoftwareStudio.Service.Workflow.FailureTriageService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 将结构化失败路由到受限工具会话，并保留候选修复回滚所需的完整基线。
 */
@Service
public class WorkspaceRepairService {

    private static final int MAX_FAILURE_CONTEXT_CHARS = 36_000;

    private final FailureTriageService failureTriageService;
    private final WorkspaceAgentRuntime workspaceAgentRuntime;
    private final RunJournalService runJournalService;

    public WorkspaceRepairService(
            FailureTriageService failureTriageService,
            WorkspaceAgentRuntime workspaceAgentRuntime,
            RunJournalService runJournalService) {
        this.failureTriageService = failureTriageService;
        this.workspaceAgentRuntime = workspaceAgentRuntime;
        this.runJournalService = runJournalService;
    }

    /**
     * 消费一次修复预算，在真实目录中修改并验证；失败或无变更时停止本轮候选。
     */
    public void repair(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        if (!data.shouldFix || data.pendingFixLog == null || data.pendingErrorType == null) {
            return;
        }

        RepairDecision decision = failureTriageService.decide(data);
        logger.accept("   Failure triage: " + decision.reason());
        if (!decision.retryable() || !decision.llmAllowed()) {
            data.shouldFix = false;
            runJournalService.recordRepair(data, decision, false, List.of(), decision.reason());
            return;
        }

        data.consumeRepairBudget();
        captureBaseline(data);
        WorkspaceAgentMode mode = modeFor(decision.target());
        String objective = repairObjective(data, decision);
        boolean completed = workspaceAgentRuntime.run(data, mode, Set.of(), objective, logger);
        boolean changed = completed && data.repairCandidateChangedFiles != null
                && !data.repairCandidateChangedFiles.isEmpty();

        if (!changed) {
            data.noChangeStopCount++;
            data.repairStopRequested = true;
            data.shouldFix = false;
            data.markRepairStopped(completed ? "NO_CHANGE" : "TOOL_SESSION_INCOMPLETE");
            clearBaseline(data);
        } else {
            invalidateVerificationEvidence(data);
        }
        runJournalService.recordRepair(
                data,
                decision,
                changed,
                changed ? List.copyOf(data.repairCandidateChangedFiles) : List.of(),
                changed ? "工具修复已修改并局部验证真实文件。" : "工具修复未形成可验证候选。" );
    }

    private WorkspaceAgentMode modeFor(RepairTarget target) {
        return switch (target) {
            case TESTS -> WorkspaceAgentMode.REPAIR_TEST;
            case CONTRACT -> WorkspaceAgentMode.REPAIR_CONTRACT;
            case IMPLEMENTATION, PROJECT_PROFILE -> WorkspaceAgentMode.REPAIR_IMPLEMENTATION;
            case ARCHITECTURE, ENVIRONMENT, STOP -> throw new IllegalArgumentException(
                    "Non-repairable target reached tool repair: " + target);
        };
    }

    private String repairObjective(SoftwareStudioWorkflowData data, RepairDecision decision) {
        String requiredVerification = switch (decision.target()) {
            case TESTS -> "Run run_tests after every edit set and use its newest output.";
            case CONTRACT -> "Run compile_main and validate_contract after every edit set.";
            default -> "Run compile_main after every edit set and use its newest compiler output.";
        };
        return """
                Repair the current project failure. Failure ownership: %s.

                Real failing command and output:
                %s

                Product requirements:
                %s

                Required contract:
                %s

                Search and read the actual owner files before editing. Treat the tool output as authoritative; do not
                guess APIs, DTO fields, constructors, routes or test expectations. Make the smallest coherent repair
                across the owning files. Do not weaken legitimate tests or remove required behavior. %s Call
                complete_stage only after the latest change has passed the required real verification.
                """.formatted(
                decision.target(),
                boundedFailureContext(data.pendingFixLog),
                data.prd,
                data.contract,
                requiredVerification);
    }

    private String boundedFailureContext(String failure) {
        if (failure == null || failure.length() <= MAX_FAILURE_CONTEXT_CHARS) {
            return failure == null ? "" : failure;
        }
        int head = 6_000;
        return failure.substring(0, head)
                + "\n... output truncated by platform; newest tail follows ...\n"
                + failure.substring(failure.length() - (MAX_FAILURE_CONTEXT_CHARS - head));
    }

    private void captureBaseline(SoftwareStudioWorkflowData data) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (SourceCode code : data.codes) {
            if (code != null && code.filename() != null) {
                snapshot.put(code.filename().replace('\\', '/'), code.code() == null ? "" : code.code());
            }
        }
        data.repairBaselineCodes = snapshot;
        data.repairCandidateChangedFiles = new ArrayList<>();
        data.repairBaselineValidationWarnings = new ArrayList<>(data.validationWarnings);
        data.repairBaselineQualityPolicyResult = data.qualityPolicyResult;
        data.repairBaselineVerificationResult = data.verificationResult;
        data.repairBaselineFailureKind = data.pendingFailureKind;
        data.repairBaselineFixLog = data.pendingFixLog;
        data.repairBaselineErrorType = data.pendingErrorType;
        data.repairBaselineExecutionResult = data.executionResult;
        data.repairBaselineTestResult = data.testResult;
        data.repairBaselineFailureFingerprint = data.lastFailureFingerprint;
    }

    private void invalidateVerificationEvidence(SoftwareStudioWorkflowData data) {
        data.verificationResult = VerificationResult.empty();
        data.executionResult = "";
        data.testResult = "";
        data.success = false;
    }

    private void clearBaseline(SoftwareStudioWorkflowData data) {
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
