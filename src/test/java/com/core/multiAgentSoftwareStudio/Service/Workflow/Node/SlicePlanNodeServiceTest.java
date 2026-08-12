package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Service.Generation.SlicePlanningService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlicePlanNodeServiceTest {

    /**
     * 切片计划生成后必须按真实切片数建立 v3 项目上限。
     */
    @Test
    void initializesProgressAwareRepairBudgetAfterPlanning() {
        SlicePlanningService planningService = mock(SlicePlanningService.class);
        RunJournalService journalService = mock(RunJournalService.class);
        ProjectContract emptyContract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        when(planningService.createPlan(any(), any())).thenReturn(new SliceDeliveryPlan(List.of(
                new DeliverySlice("one", "One", List.of(), List.of(), emptyContract, List.of(), List.of()),
                new DeliverySlice("two", "Two", List.of(), List.of(), emptyContract, List.of(), List.of()),
                new DeliverySlice("three", "Three", List.of(), List.of(), emptyContract, List.of(), List.of())),
                List.of(), "vertical-slice-test"));
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");

        new SlicePlanNodeService(planningService, journalService).execute(data, message -> { });

        assertEquals(5, data.repairBudget.maxLlmRepairs());
        assertEquals(4, data.repairBudget.maxRepairsPerSlice());
        assertEquals(1, data.repairBudget.reservedForFinalVerification());
        assertEquals("progress-aware-repair-budget-v3", data.repairBudget.policyVersion());
    }
}
