package com.core.multiAgentSoftwareStudio.Model.Workflow;

import com.core.multiAgentSoftwareStudio.Config.RepairBudgetConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepairBudgetTest {

    /**
     * 阶段四预算应随切片数增长，但始终受项目硬上限约束。
     */
    @Test
    void createsProgressAwareBudgetFromSlicePlan() {
        RepairBudget twoSlices = RepairBudget.forSlicePlan(2);
        RepairBudget fourSlices = RepairBudget.forSlicePlan(4);
        RepairBudget manySlices = RepairBudget.forSlicePlan(20);

        assertEquals(Math.min(
                RepairBudgetConfig.MAX_PROJECT_LLM_REPAIRS,
                2 + RepairBudgetConfig.EXTRA_REPAIRS_BEYOND_SLICE_COUNT), twoSlices.maxLlmRepairs());
        assertEquals(Math.min(
                RepairBudgetConfig.MAX_REPAIRS_PER_SLICE,
                twoSlices.maxLlmRepairs()), twoSlices.maxRepairsPerSlice());
        assertEquals(Math.min(
                RepairBudgetConfig.MAX_PROJECT_LLM_REPAIRS,
                4 + RepairBudgetConfig.EXTRA_REPAIRS_BEYOND_SLICE_COUNT), fourSlices.maxLlmRepairs());
        assertEquals(RepairBudgetConfig.MAX_PROJECT_LLM_REPAIRS, manySlices.maxLlmRepairs());
        assertEquals(RepairBudget.POLICY_VERSION, fourSlices.policyVersion());
    }

    /**
     * 同一阻塞切片可以跨越编译、测试和契约错误继续收敛，但仍受单切片安全上限保护。
     */
    @Test
    void allowsProgressiveRepairsWithinSliceSafetyLimit() {
        RepairBudget budget = RepairBudget.forSlicePlan(4);

        for (int index = 0; index < 4; index++) {
            assertTrue(budget.canRepair("slice-a", false, true));
            budget.consume("slice-a", false, true);
        }

        assertFalse(budget.canRepair("slice-a", false, true));
        assertTrue(budget.canRepair("slice-b", false, true));
        budget.consume("slice-b", false, true);
        assertTrue(budget.canRepair("FINAL", true));
    }

    /**
     * 显式预算应为最终验证保留额度，切片未获借用权限时不能提前消费。
     */
    @Test
    void reservesConfiguredBudgetForFinalVerification() {
        RepairBudget budget = new RepairBudget(2, 2, 1);

        assertEquals(2, budget.maxLlmRepairs());
        assertEquals(1, budget.reservedForFinalVerification());
        assertTrue(budget.canRepair("slice-a", false));

        budget.consume("slice-a", false);

        assertFalse(budget.canRepair("slice-b", false));
        assertTrue(budget.canRepair("FINAL", true));
        budget.consume("FINAL", true);
        assertEquals(2, budget.usedLlmRepairs());
    }

    /**
     * 首切片仍未接受时可以借用最终预留，但总修复次数仍不得超过全局硬上限。
     */
    @Test
    void borrowsFinalReserveOnlyForBlockedInitialSlice() {
        RepairBudget budget = new RepairBudget(2, 2, 1);
        budget.consume("slice-a", false, true);

        assertTrue(budget.canRepair("slice-a", false, true));
        budget.consume("slice-a", false, true);

        assertTrue(budget.finalReserveBorrowed());
        assertFalse(budget.canRepair("FINAL", true));
        assertEquals(2, budget.usedLlmRepairs());
    }

    /**
     * 后续切片已经阻塞时也应借用最终预留，否则流程停止后该额度永远无法使用。
     */
    @Test
    void workflowBorrowsFinalReserveForBlockedLaterSlice() {
        var data = new com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData("test");
        data.repairBudget = new RepairBudget(2, 2, 1);
        data.acceptedSliceIds.add("slice-a");
        data.consumeRepairBudget();

        assertTrue(data.canRepairNow());
        data.currentSliceIndex = 1;
        data.consumeRepairBudget();

        assertEquals(2, data.repairBudget.usedLlmRepairs());
        assertTrue(data.repairBudget.finalReserveBorrowed());
        assertFalse(data.canRepairNow());
    }

    /**
     * 工作流因无变化主动停止时不能把仍可使用的保留额度标记成预算耗尽。
     */
    @Test
    void stopRequestDoesNotMarkAvailableBudgetAsExhausted() {
        var data = new com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData("test");
        data.consumeRepairBudget();
        data.repairStopRequested = true;

        data.markRepairBudgetUnavailable();

        assertNull(data.repairBudget.exhaustedAt());
    }
}
