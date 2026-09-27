package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Service.Generation.SlicePlanningService;
import com.core.multiAgentSoftwareStudio.Service.Generation.BusinessMilestoneRefiner;
import com.core.multiAgentSoftwareStudio.Model.Generation.GenerationBatch;
import com.core.multiAgentSoftwareStudio.Model.Generation.GenerationPlan;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 将项目架构和契约转换为按业务能力排序的垂直切片计划。
 */
@Service
public class SlicePlanNodeService {

    private final SlicePlanningService slicePlanningService;
    private final BusinessMilestoneRefiner milestoneRefiner;
    public SlicePlanNodeService(SlicePlanningService slicePlanningService,
                                BusinessMilestoneRefiner milestoneRefiner) {
        this.slicePlanningService = slicePlanningService;
        this.milestoneRefiner = milestoneRefiner;
    }

    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        data.sliceDeliveryPlan = milestoneRefiner.refine(slicePlanningService.createPlan(data.structure, data.contract));
        // 业务切片已有依赖顺序；生成计划复用同一顺序，不再另按技术层重复分批。
        data.generationPlan = new GenerationPlan(data.sliceDeliveryPlan.slices().stream()
                .map(slice -> new GenerationBatch(slice.name(), "business-milestone", slice.files()))
                .toList());
        data.currentSliceIndex = 0;
        logger.accept("3. Vertical slice plan created with " + data.sliceDeliveryPlan.slices().size() + " slices.");
        data.sliceDeliveryPlan.slices().forEach(slice -> logger.accept(
                "   Slice `" + slice.name() + "`: files=" + slice.files().size()
                        + ", acceptance=" + slice.acceptanceCriteria().size()));
        return data;
    }
}
