package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Service.Generation.SlicePlanningService;
import com.core.multiAgentSoftwareStudio.Model.Workflow.RepairBudget;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 将项目架构和契约转换为按业务能力排序的垂直切片计划。
 */
@Service
public class SlicePlanNodeService {

    private final SlicePlanningService slicePlanningService;
    private final RunJournalService runJournalService;

    public SlicePlanNodeService(SlicePlanningService slicePlanningService, RunJournalService runJournalService) {
        this.slicePlanningService = slicePlanningService;
        this.runJournalService = runJournalService;
    }

    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        data.sliceDeliveryPlan = slicePlanningService.createPlan(data.structure, data.contract);
        data.currentSliceIndex = 0;
        // 切片数量确定后再建立真实修复预算，确保项目额度与实际交付规模一致。
        data.repairBudget = RepairBudget.forSlicePlan(data.sliceDeliveryPlan.slices().size());
        data.currentAttempt = 1;
        logger.accept("3. Vertical slice plan created with " + data.sliceDeliveryPlan.slices().size() + " slices.");
        logger.accept("   Repair budget: project=" + data.repairBudget.maxLlmRepairs()
                + ", sliceSafety=" + data.repairBudget.maxRepairsPerSlice()
                + ", finalReserve=" + data.repairBudget.reservedForFinalVerification()
                + ", policy=" + data.repairBudget.policyVersion());
        data.sliceDeliveryPlan.slices().forEach(slice -> logger.accept(
                "   Slice `" + slice.name() + "`: files=" + slice.files().size()
                        + ", acceptance=" + slice.acceptanceCriteria().size()));
        if (data.currentSlice() != null) {
            runJournalService.recordSliceStarted(data);
        }
        return data;
    }
}
