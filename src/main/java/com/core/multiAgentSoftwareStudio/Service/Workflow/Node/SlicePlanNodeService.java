package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Service.Generation.SlicePlanningService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 将项目架构和契约转换为按业务能力排序的垂直切片计划。
 */
@Service
public class SlicePlanNodeService {

    private final SlicePlanningService slicePlanningService;
    public SlicePlanNodeService(SlicePlanningService slicePlanningService) {
        this.slicePlanningService = slicePlanningService;
    }

    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        data.sliceDeliveryPlan = slicePlanningService.createPlan(data.structure, data.contract);
        data.currentSliceIndex = 0;
        logger.accept("3. Vertical slice plan created with " + data.sliceDeliveryPlan.slices().size() + " slices.");
        data.sliceDeliveryPlan.slices().forEach(slice -> logger.accept(
                "   Slice `" + slice.name() + "`: files=" + slice.files().size()
                        + ", acceptance=" + slice.acceptanceCriteria().size()));
        return data;
    }
}
