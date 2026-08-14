package com.core.multiAgentSoftwareStudio.Service.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityGateSeverity;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairDecision;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairTarget;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStepResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowJournalEntry;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowJournalEventType;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowRunSummary;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SliceDeliverySummary;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 维护轻量可回放 Run Journal，并向基准报告提供聚合统计。
 */
@Service
public class RunJournalService {

    public void recordSliceStarted(SoftwareStudioWorkflowData data) {
        add(data, WorkflowJournalEventType.SLICE_STARTED, FailureKind.NONE, null,
                true, null, List.of(), List.of(), "slice-started");
    }

    public void recordSliceAccepted(SoftwareStudioWorkflowData data) {
        add(data, WorkflowJournalEventType.SLICE_ACCEPTED, FailureKind.NONE, null,
                true, null, List.of(), List.of(), "slice-accepted");
    }

    public void recordRegressionFailure(SoftwareStudioWorkflowData data) {
        add(data, WorkflowJournalEventType.SLICE_REGRESSION_FAILED, data.pendingFailureKind, null,
                false, null, List.of(), List.of(), "accepted-slice-regression");
    }

    public void recordPreflight(SoftwareStudioWorkflowData data, ProjectQualityPolicyResult result) {
        List<String> failedGateIds = result == null ? List.of() : result.blockingFindings().stream()
                .map(finding -> finding.gate() == null || finding.gate().isBlank() ? "UNKNOWN" : finding.gate())
                .toList();
        FailureKind failureKind = result == null || result.passed()
                ? FailureKind.NONE
                : result.findings().stream()
                        .filter(finding -> finding.severity() == QualityGateSeverity.BLOCK)
                        .map(finding -> finding.failureKind() == null ? FailureKind.UNKNOWN : finding.failureKind())
                        .findFirst()
                        .orElse(FailureKind.UNKNOWN);
        add(data, WorkflowJournalEventType.PREFLIGHT, failureKind, null,
                result != null && result.passed(), null,
                result == null ? List.of() : result.normalizedFiles(), failedGateIds,
                result == null ? "" : "findings=" + result.findings().size());
    }

    public void recordVerification(SoftwareStudioWorkflowData data, VerificationStepResult step) {
        WorkflowJournalEventType eventType = step.stage() == com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStage.TEST
                ? WorkflowJournalEventType.TEST_VERIFICATION
                : WorkflowJournalEventType.BUILD_VERIFICATION;
        add(data, eventType, step.failureKind(), null, step.passed(), null, List.of(), List.of(), step.evidence());
    }

    public void recordRepair(
            SoftwareStudioWorkflowData data,
            RepairDecision decision,
            boolean changed,
            List<String> changedFiles,
            String evidence) {
        add(data, WorkflowJournalEventType.REPAIR, data.pendingFailureKind,
                decision == null ? RepairTarget.STOP : decision.target(), changed,
                decision == null ? null : decision.failureFingerprint(), changedFiles, List.of(), evidence);
    }

    public WorkflowRunSummary summarize(SoftwareStudioWorkflowData data) {
        if (data == null || data.runJournal == null) {
            return WorkflowRunSummary.empty();
        }
        int preflightChecks = (int) data.runJournal.stream()
                .filter(entry -> entry.eventType() == WorkflowJournalEventType.PREFLIGHT).count();
        int verificationSteps = (int) data.runJournal.stream()
                .filter(entry -> entry.eventType() == WorkflowJournalEventType.BUILD_VERIFICATION
                        || entry.eventType() == WorkflowJournalEventType.TEST_VERIFICATION)
                .count();
        int repairAttempts = (int) data.runJournal.stream()
                .filter(entry -> entry.eventType() == WorkflowJournalEventType.REPAIR).count();
        int normalizedFileChanges = data.runJournal.stream()
                .filter(entry -> entry.eventType() == WorkflowJournalEventType.PREFLIGHT)
                .mapToInt(entry -> entry.changedFiles().size())
                .sum();
        Map<FailureKind, Long> failures = data.runJournal.stream()
                .filter(entry -> entry.eventType() != WorkflowJournalEventType.REPAIR)
                .filter(entry -> !entry.successful())
                .filter(entry -> entry.failureKind() != null && entry.failureKind() != FailureKind.NONE)
                .collect(Collectors.groupingBy(
                        WorkflowJournalEntry::failureKind,
                        () -> new EnumMap<>(FailureKind.class),
                        Collectors.counting()));
        Map<RepairTarget, Long> repairTargets = data.runJournal.stream()
                .filter(entry -> entry.eventType() == WorkflowJournalEventType.REPAIR)
                .filter(entry -> entry.repairTarget() != null)
                .collect(Collectors.groupingBy(
                        WorkflowJournalEntry::repairTarget,
                        () -> new EnumMap<>(RepairTarget.class),
                        Collectors.counting()));
        Map<String, Long> failedGateCounts = data.runJournal.stream()
                .filter(entry -> entry.eventType() == WorkflowJournalEventType.PREFLIGHT)
                .flatMap(entry -> entry.gateIds().stream())
                .collect(Collectors.groupingBy(Function.identity(), LinkedHashMap::new, Collectors.counting()));
        int acceptedSlices = data.acceptedSliceIds == null ? 0 : data.acceptedSliceIds.size();
        int regressionFailures = (int) data.runJournal.stream()
                .filter(entry -> entry.eventType() == WorkflowJournalEventType.SLICE_REGRESSION_FAILED).count();
        int firstPassAccepted = data.success && data.repairBudget != null
                && data.repairBudget.usedLlmRepairs() == 0 ? acceptedSlices : 0;
        SliceDeliverySummary sliceSummary = new SliceDeliverySummary(
                data.sliceDeliveryPlan == null ? 0 : data.sliceDeliveryPlan.slices().size(),
                acceptedSlices,
                firstPassAccepted,
                regressionFailures,
                data.repairRollbackCount,
                data.repeatedRegressionStopCount,
                data.repairBudget == null ? 0 : data.repairBudget.usedLlmRepairs(),
                data.repairBudget == null ? 0 : data.repairBudget.maxLlmRepairs(),
                data.repairBudget == null ? 0 : data.repairBudget.maxRepairsPerSlice(),
                data.repairBudget == null ? 0 : data.repairBudget.reservedForFinalVerification(),
                data.repairBudget == null ? 0 : data.repairBudget.finalRepairCount(),
                data.repairBudget != null && data.repairBudget.finalReserveBorrowed(),
                data.repairBudget == null ? Map.of() : data.repairBudget.sliceRepairCounts(),
                data.repairBudget == null ? null : data.repairBudget.exhaustedAt(),
                data.repairStoppedAt,
                data.repairStopReason,
                data.generatedTestFileCount,
                data.admittedTestFileCount,
                data.acceptedTestFiles.size(),
                data.skippedFutureTypeTestCount,
                data.skippedFutureResourceTestCount,
                data.skippedAcceptedTestFileCount,
                data.repairBudget == null ? "" : data.repairBudget.policyVersion(),
                data.sliceDeliveryPlan == null ? "" : data.sliceDeliveryPlan.policyVersion(),
                data.structure == null ? "" : data.structure.projectType(),
                data.projectProfile == null ? "" : data.projectProfile.projectType(),
                data.projectProfile == null ? "" : data.projectProfile.id());
        return new WorkflowRunSummary(preflightChecks, verificationSteps, repairAttempts,
                normalizedFileChanges, data.repeatedFailureStopCount, data.noChangeStopCount,
                failures, repairTargets, failedGateCounts, sliceSummary);
    }

    private void add(
            SoftwareStudioWorkflowData data,
            WorkflowJournalEventType eventType,
            FailureKind failureKind,
            RepairTarget repairTarget,
            boolean successful,
            String failureFingerprint,
            List<String> changedFiles,
            List<String> gateIds,
            String evidence) {
        data.runJournal.add(new WorkflowJournalEntry(
                data.runJournal.size() + 1,
                Instant.now().toString(),
                eventType,
                data.currentSliceId(),
                data.currentAttempt,
                failureKind == null ? FailureKind.UNKNOWN : failureKind,
                repairTarget,
                successful,
                failureFingerprint,
                changedFiles,
                gateIds,
                evidence));
    }
}
