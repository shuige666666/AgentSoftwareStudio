package com.core.multiAgentSoftwareStudio.Model.Workflow;

import java.io.Serializable;
import java.util.Map;

/**
 * 基准报告使用的 Run Journal 聚合结果，不保存单次失败明细。
 */
public record WorkflowRunSummary(
        int preflightChecks,
        int verificationSteps,
        int repairAttempts,
        int normalizedFileChanges,
        int repeatedFailureStops,
        int noChangeStops,
        Map<FailureKind, Long> failureCounts,
        Map<RepairTarget, Long> repairTargetCounts,
        Map<String, Long> failedGateCounts,
        SliceDeliverySummary sliceDelivery) implements Serializable {

    private static final long serialVersionUID = 1L;

    public WorkflowRunSummary {
        failureCounts = failureCounts == null ? Map.of() : Map.copyOf(failureCounts);
        repairTargetCounts = repairTargetCounts == null ? Map.of() : Map.copyOf(repairTargetCounts);
        failedGateCounts = failedGateCounts == null ? Map.of() : Map.copyOf(failedGateCounts);
        sliceDelivery = sliceDelivery == null ? SliceDeliverySummary.empty() : sliceDelivery;
    }

    /**
     * 保留阶段一至三使用的八参数构造方式，新增门禁聚合字段默认置空。
     */
    public WorkflowRunSummary(
            int preflightChecks,
            int verificationSteps,
            int repairAttempts,
            int normalizedFileChanges,
            int repeatedFailureStops,
            int noChangeStops,
            Map<FailureKind, Long> failureCounts,
            Map<RepairTarget, Long> repairTargetCounts) {
        this(preflightChecks, verificationSteps, repairAttempts, normalizedFileChanges,
                repeatedFailureStops, noChangeStops, failureCounts, repairTargetCounts, Map.of(),
                SliceDeliverySummary.empty());
    }

    /**
     * 保留旧的聚合构造方式，便于已有测试和外围代码逐步迁移。
     */
    public WorkflowRunSummary(
            int preflightChecks,
            int verificationSteps,
            int repairAttempts,
            int repeatedFailureStops,
            int noChangeStops,
            Map<FailureKind, Long> failureCounts) {
        this(preflightChecks, verificationSteps, repairAttempts, 0,
                repeatedFailureStops, noChangeStops, failureCounts, Map.of(), Map.of(),
                SliceDeliverySummary.empty());
    }

    public WorkflowRunSummary(
            int preflightChecks,
            int verificationSteps,
            int repairAttempts,
            int normalizedFileChanges,
            int repeatedFailureStops,
            int noChangeStops,
            Map<FailureKind, Long> failureCounts,
            Map<RepairTarget, Long> repairTargetCounts,
            Map<String, Long> failedGateCounts) {
        this(preflightChecks, verificationSteps, repairAttempts, normalizedFileChanges,
                repeatedFailureStops, noChangeStops, failureCounts, repairTargetCounts, failedGateCounts,
                SliceDeliverySummary.empty());
    }

    public static WorkflowRunSummary empty() {
        return new WorkflowRunSummary(0, 0, 0, 0, 0, 0, Map.of(), Map.of(), Map.of(),
                SliceDeliverySummary.empty());
    }
}
