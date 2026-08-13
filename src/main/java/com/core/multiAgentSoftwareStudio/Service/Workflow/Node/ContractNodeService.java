package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Agent.ContractAgent;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectContractMergeService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 负责执行接口契约规划节点，并将契约要求补充回架构蓝图。
 */
@Service
public class ContractNodeService {

    private final ContractAgent contractAgent;
    private final ProjectContractMergeService projectContractMergeService;

    /**
     * 注入契约 Agent 和契约合并服务。
     */
    public ContractNodeService(ContractAgent contractAgent,
            ProjectContractMergeService projectContractMergeService) {
        this.contractAgent = contractAgent;
        this.projectContractMergeService = projectContractMergeService;
    }

    /**
     * 执行接口契约规划节点，生成接口文档并补齐契约要求的文件蓝图
     */
    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        logger.accept("2.5. Contract planner is designing API, view, and frontend interaction contracts.");
        data.contract = contractAgent.designContract(data.prd, data.structure);
        data.structure = projectContractMergeService.merge(data.structure, data.contract);
        int endpointCount = data.contract == null || data.contract.endpoints() == null ? 0
                : data.contract.endpoints().size();
        int extraFileCount = data.contract == null || data.contract.additionalFiles() == null ? 0
                : data.contract.additionalFiles().size();
        logger.accept("Contract ready with " + endpointCount + " endpoints and " + extraFileCount
                + " supplemental files.");
        return data;
    }
}
