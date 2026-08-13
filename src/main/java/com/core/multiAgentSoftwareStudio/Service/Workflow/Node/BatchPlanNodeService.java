package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Model.Generation.GenerationBatch;
import com.core.multiAgentSoftwareStudio.Service.Generation.BatchPlanningService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 负责执行批次规划节点，将架构蓝图转换成可执行生成计划。
 */
@Service
public class BatchPlanNodeService {

    private final BatchPlanningService batchPlanningService;

    /**
     * 注入批次规划服务。
     */
    public BatchPlanNodeService(BatchPlanningService batchPlanningService) {
        this.batchPlanningService = batchPlanningService;
    }

    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        // 这个节点把“架构蓝图”转换成“可执行计划”。
        // 也就是把文件从静态描述，变成真正的生成批次序列。
        data.generationPlan = batchPlanningService.createPlan(data.structure);
        data.currentBatchIndex = 0;
        logger.accept("3. Batch plan created with " + data.generationPlan.batches().size() + " batches.");
        for (int i = 0; i < data.generationPlan.batches().size(); i++) {
            GenerationBatch batch = data.generationPlan.batches().get(i);
            logger.accept("   Batch " + (i + 1) + ": " + batch.name() + " [" + batch.layer() + "] -> "
                    + batch.files().size() + " files");
        }
        return data;
    }
}
