package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Service.Contract.ContractValidationService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

/**
 * 负责执行项目持久化节点，在统一契约校验后将生成项目写入本地工作区。
 */
@Service
public class PersistenceNodeService {

    private final ContractValidationService contractValidationService;
    private final WorkspaceService workspaceService;

    /**
     * 注入契约校验服务和工作区文件服务。
     */
    public PersistenceNodeService(ContractValidationService contractValidationService,
            WorkspaceService workspaceService) {
        this.contractValidationService = contractValidationService;
        this.workspaceService = workspaceService;
    }

    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        // 到这里才统一落盘，而不是每个批次都写一次磁盘。
        // 这样可以减少中间态文件干扰，也让最终修复更集中。
        List<String> projectWarnings = contractValidationService.validateProject(data.codes, data.contract);
        if (!projectWarnings.isEmpty()) {
            logger.accept("   Project contract validation found " + projectWarnings.size() + " warnings.");
            projectWarnings.forEach(warning -> logger.accept("   - " + warning));
            data.validationWarnings.addAll(projectWarnings);
        }

        data.projectPath = workspaceService.saveProjectToDisk(
                safeValue(data.prd == null ? null : data.prd.projectName(), "GeneratedProject"),
                data.codes,
                logger).toString();
        logger.accept("6. Persisted generated project to disk.");
        return data;
    }

    /**
     * 在值为空时返回兜底文本
     */
    private String safeValue(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
