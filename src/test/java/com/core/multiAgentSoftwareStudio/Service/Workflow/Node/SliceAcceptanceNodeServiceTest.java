package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SliceAcceptanceNodeServiceTest {

    private final SliceAcceptanceNodeService service = new SliceAcceptanceNodeService(new RunJournalService());

    /**
     * 接受切片时固化其回归测试并推进游标，但不提前宣告整个项目成功。
     */
    @Test
    void acceptsVerifiedSliceAndProtectsItsRegressionTests() {
        SoftwareStudioWorkflowData data = dataWithTwoSlices();
        String acceptedTest = "src/test/java/com/example/PollSliceTest.java";
        data.currentSliceTestFiles.add(acceptedTest);
        data.currentSliceVerified = true;

        service.execute(data, message -> { });

        assertEquals(1, data.currentSliceIndex);
        assertEquals(List.of("poll"), data.acceptedSliceIds);
        assertEquals(List.of(acceptedTest), data.acceptedTestFiles);
        assertFalse(data.canModifyInCurrentSlice(acceptedTest));
        assertFalse(data.success);
        assertEquals(1, data.runJournal.stream()
                .filter(entry -> entry.eventType().name().equals("SLICE_ACCEPTED")).count());
    }

    /**
     * 全部切片接受后切换到最终全量验证，并清除切片级成功状态。
     */
    @Test
    void preparesFinalVerificationAfterAllSlices() {
        SoftwareStudioWorkflowData data = dataWithTwoSlices();
        data.currentSliceIndex = 2;
        data.currentSliceVerified = true;
        data.success = true;

        service.prepareFinalVerification(data, message -> { });

        assertTrue(data.finalVerificationStarted);
        assertTrue(data.currentSlicePersisted);
        assertFalse(data.currentSliceVerified);
        assertFalse(data.success);
        assertTrue(data.activeTestFiles().isEmpty());
    }

    private SoftwareStudioWorkflowData dataWithTwoSlices() {
        ProjectContract emptyContract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        DeliverySlice first = new DeliverySlice(
                "poll", "Poll", List.of(), List.of(), emptyContract, List.of(), List.of());
        DeliverySlice second = new DeliverySlice(
                "result", "Result", List.of(), List.of(), emptyContract, List.of("poll"), List.of());
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(first, second), List.of(), "test-policy");
        return data;
    }
}
