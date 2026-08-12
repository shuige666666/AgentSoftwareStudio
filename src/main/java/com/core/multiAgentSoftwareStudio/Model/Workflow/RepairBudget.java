package com.core.multiAgentSoftwareStudio.Model.Workflow;

import com.core.multiAgentSoftwareStudio.Config.RepairBudgetConfig;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 维护全流程共享的 LLM 修复硬预算，防止每个切片复制完整重试次数。
 */
public class RepairBudget implements Serializable {

    private static final long serialVersionUID = 1L;
    public static final String POLICY_VERSION = "progress-aware-repair-budget-v3";

    private final int maxLlmRepairs;
    private final int maxRepairsPerSlice;
    private final int reservedForFinalVerification;
    private int usedLlmRepairs;
    private int finalRepairCount;
    private final Map<String, Integer> sliceRepairCounts = new LinkedHashMap<>();
    private String exhaustedAt;
    private boolean finalReserveBorrowed;

    public RepairBudget(int maxLlmRepairs, int maxRepairsPerSlice, int reservedForFinalVerification) {
        this.maxLlmRepairs = Math.max(0, maxLlmRepairs);
        this.maxRepairsPerSlice = Math.max(1, maxRepairsPerSlice);
        this.reservedForFinalVerification = Math.max(0,
                Math.min(reservedForFinalVerification, this.maxLlmRepairs));
    }

    /**
     * 根据实际切片数量创建阶段四预算。
     */
    public static RepairBudget forSlicePlan(int plannedSlices) {
        int safeSliceCount = Math.max(1, plannedSlices);
        int projectLimit = Math.min(
                RepairBudgetConfig.MAX_PROJECT_LLM_REPAIRS,
                safeSliceCount + RepairBudgetConfig.EXTRA_REPAIRS_BEYOND_SLICE_COUNT);
        int sliceSafetyLimit = Math.min(RepairBudgetConfig.MAX_REPAIRS_PER_SLICE, projectLimit);
        int finalReserve = Math.min(RepairBudgetConfig.RESERVED_FOR_FINAL_VERIFICATION, projectLimit);
        return new RepairBudget(projectLimit, sliceSafetyLimit, finalReserve);
    }

    public boolean canRepair(String sliceId, boolean finalVerification) {
        return canRepair(sliceId, finalVerification, false);
    }

    /**
     * 当前切片阻塞且无法到达最终验证时允许借用最终预留，避免流程停止后仍遗留不可使用的额度。
     */
    public boolean canRepair(String sliceId, boolean finalVerification, boolean allowFinalReserveBorrow) {
        if (usedLlmRepairs >= maxLlmRepairs) {
            return false;
        }
        if (finalVerification) {
            return true;
        }
        if (!allowFinalReserveBorrow && usedLlmRepairs >= maxLlmRepairs - reservedForFinalVerification) {
            return false;
        }
        String safeSliceId = safeSliceId(sliceId);
        return sliceRepairCounts.getOrDefault(safeSliceId, 0) < maxRepairsPerSlice;
    }

    /**
     * 在修复 Agent 已经发起一次调用后消费预算，无论它最终是否产生文件差异。
     */
    public void consume(String sliceId, boolean finalVerification) {
        consume(sliceId, finalVerification, false);
    }

    public void consume(String sliceId, boolean finalVerification, boolean allowFinalReserveBorrow) {
        if (!canRepair(sliceId, finalVerification, allowFinalReserveBorrow)) {
            markExhausted(sliceId, finalVerification);
            throw new IllegalStateException("LLM repair budget is exhausted");
        }
        usedLlmRepairs++;
        if (finalVerification) {
            finalRepairCount++;
        } else {
            sliceRepairCounts.merge(safeSliceId(sliceId), 1, Integer::sum);
            if (allowFinalReserveBorrow && usedLlmRepairs > maxLlmRepairs - reservedForFinalVerification) {
                finalReserveBorrowed = true;
            }
        }
    }

    public void markExhausted(String sliceId, boolean finalVerification) {
        if (exhaustedAt == null) {
            exhaustedAt = finalVerification ? "FINAL_VERIFICATION" : safeSliceId(sliceId);
        }
    }

    private String safeSliceId(String sliceId) {
        return sliceId == null || sliceId.isBlank() ? "UNSCOPED" : sliceId;
    }

    public int maxLlmRepairs() {
        return maxLlmRepairs;
    }

    public int maxRepairsPerSlice() {
        return maxRepairsPerSlice;
    }

    public int reservedForFinalVerification() {
        return reservedForFinalVerification;
    }

    public int usedLlmRepairs() {
        return usedLlmRepairs;
    }

    public int finalRepairCount() {
        return finalRepairCount;
    }

    public Map<String, Integer> sliceRepairCounts() {
        return Map.copyOf(sliceRepairCounts);
    }

    public String exhaustedAt() {
        return exhaustedAt;
    }

    public boolean finalReserveBorrowed() {
        return finalReserveBorrowed;
    }

    public String policyVersion() {
        return POLICY_VERSION;
    }
}
