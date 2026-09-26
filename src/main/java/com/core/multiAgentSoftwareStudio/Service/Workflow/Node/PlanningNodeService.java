package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Agent.PlanningAgent;
import com.core.multiAgentSoftwareStudio.Model.Generation.PlanningConflictDecision;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectContractMergeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 在同一规划角色中依次完成需求摘要与总体设计，复用现有状态和契约合并逻辑。
 */
@Service
public class PlanningNodeService {
    private final PlanningAgent planningAgent;
    private final ProjectContractMergeService mergeService;
    private final PlanningArtifactPersistenceService persistenceService;

    public PlanningNodeService(PlanningAgent planningAgent, ProjectContractMergeService mergeService,
                               PlanningArtifactPersistenceService persistenceService) {
        this.planningAgent = planningAgent;
        this.mergeService = mergeService;
        this.persistenceService = persistenceService;
    }

    /**
     * 第一步保留原始需求，生成带稳定行为编号的摘要。
     */
    public SoftwareStudioWorkflowData requirements(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        logger.accept("1. PlanningAgent is summarizing required behaviors.");
        data.prd = planningAgent.summarizeRequirements(data.userRequest);
        logger.accept("   Required behaviors: " + data.prd.userStories().size() + ".");
        return data;
    }

    /**
     * 第二步一次给出领域设计与公开接口，不再让旧架构和契约角色互相修订。
     */
    public SoftwareStudioWorkflowData design(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        logger.accept("2. PlanningAgent is designing the project and public contract.");
        data.planningDesign = planningAgent.design(data.userRequest, data.prd);
        data.contract = data.planningDesign.contract();
        data.structure = mergeService.merge(data.planningDesign.structure(), data.contract);
        logger.accept("   Design ready: " + data.structure.files().size() + " files, "
                + data.contract.endpoints().size() + " endpoints.");
        return data;
    }

    /** 开发会话遇到真实规划冲突时，复核并将修订后的有效契约返回同一会话。 */
    public PlanningConflictDecision reviewConflict(SoftwareStudioWorkflowData data, String evidence,
                                                   Consumer<String> logger) {
        PlanningConflictDecision decision = planningAgent.reviewConflict(data.userRequest, data.prd,
                data.planningDesign, evidence);
        if (decision.revised()) {
            data.planningDesign = decision.revisedDesign();
            data.contract = decision.revisedDesign().contract();
            data.structure = mergeService.merge(decision.revisedDesign().structure(), data.contract);
            persistenceService.persistStage(data, PlanningArtifactPersistenceService.PlanningStage.CONTRACT, logger);
            logger.accept("   Planning conflict revised; development continues in the same session.");
        }
        return decision;
    }
}
