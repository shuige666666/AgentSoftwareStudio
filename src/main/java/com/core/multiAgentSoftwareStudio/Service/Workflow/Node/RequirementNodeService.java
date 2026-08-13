package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Agent.ProductManagerAgent;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

import static com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService.safeValue;

/**
 * 负责执行需求分析节点，调用产品经理 Agent 生成结构化 PRD。
 */
@Service
public class RequirementNodeService {

    private final ProductManagerAgent pmAgent;

    /**
     * 注入产品经理 Agent。
     */
    public RequirementNodeService(ProductManagerAgent pmAgent) {
        this.pmAgent = pmAgent;
    }

    /**
     * 执行产品经理节点，生成结构化 PRD
     */
    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        logger.accept("1. Product manager is analyzing the request.");
        data.prd = pmAgent.analyzeRequirement(data.userRequest);
        logger.accept("PRD created: " + safeValue(data.prd == null ? null : data.prd.projectName(), "Unnamed Project"));
        return data;
    }
}
