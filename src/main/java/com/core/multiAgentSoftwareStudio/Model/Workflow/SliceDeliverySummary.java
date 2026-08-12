package com.core.multiAgentSoftwareStudio.Model.Workflow;

import java.io.Serializable;
import java.util.Map;

/**
 * 基准报告使用的切片交付聚合指标，不包含源码和单次失败证据。
 */
public record SliceDeliverySummary(
        int plannedSlices,
        int acceptedSlices,
        int firstPassAcceptedSlices,
        int regressionFailures,
        int repairRollbackCount,
        int repeatedRegressionStopCount,
        int repairBudgetUsed,
        int repairBudgetLimit,
        int sliceRepairSafetyLimit,
        int finalVerificationReserve,
        int finalRepairCount,
        boolean finalReserveBorrowed,
        Map<String, Integer> sliceRepairCounts,
        String budgetExhaustedAt,
        String repairStoppedAt,
        String repairStopReason,
        int generatedTestFiles,
        int admittedTestFiles,
        int acceptedTestFiles,
        int skippedFutureTypeTests,
        int skippedFutureResourceTests,
        int skippedAcceptedTestFiles,
        String budgetPolicyVersion,
        String slicePolicyVersion,
        String declaredProjectType,
        String effectiveProjectType,
        String projectProfileId) implements Serializable {

    private static final long serialVersionUID = 1L;

    public SliceDeliverySummary {
        sliceRepairCounts = sliceRepairCounts == null ? Map.of() : Map.copyOf(sliceRepairCounts);
        repairStoppedAt = repairStoppedAt == null ? "" : repairStoppedAt;
        repairStopReason = repairStopReason == null ? "" : repairStopReason;
        budgetPolicyVersion = budgetPolicyVersion == null ? "" : budgetPolicyVersion;
        slicePolicyVersion = slicePolicyVersion == null ? "" : slicePolicyVersion;
        declaredProjectType = declaredProjectType == null ? "" : declaredProjectType;
        effectiveProjectType = effectiveProjectType == null ? "" : effectiveProjectType;
        projectProfileId = projectProfileId == null ? "" : projectProfileId;
    }

    public static SliceDeliverySummary empty() {
        return new SliceDeliverySummary(
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, false, Map.of(), null,
                "", "", 0, 0, 0, 0, 0, 0,
                "", "", "", "", "");
    }
}
