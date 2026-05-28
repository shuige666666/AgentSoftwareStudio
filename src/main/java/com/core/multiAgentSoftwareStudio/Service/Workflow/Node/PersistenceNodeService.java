package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Service.Contract.ContractValidationService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;

import static com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService.safeValue;

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
}
