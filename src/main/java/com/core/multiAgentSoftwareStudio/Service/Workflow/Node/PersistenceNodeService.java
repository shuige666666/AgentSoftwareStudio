package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowRunSummary;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService.PlanningStage;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;
import java.nio.file.Path;

import static com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService.safeValue;

/**
 * 负责执行项目持久化节点，在统一契约校验后将生成项目写入本地工作区。
 */
@Service
public class PersistenceNodeService {

    private final WorkspaceService workspaceService;
    private final PlanningArtifactPersistenceService planningArtifactPersistenceService;

    /**
     * 注入契约校验服务和工作区文件服务。
     */
    public PersistenceNodeService(
            WorkspaceService workspaceService,
            PlanningArtifactPersistenceService planningArtifactPersistenceService) {
        this.workspaceService = workspaceService;
        this.planningArtifactPersistenceService = planningArtifactPersistenceService;
    }

    /**
     * 在任何切片生成前初始化一次真实项目工作区。
     */
    public SoftwareStudioWorkflowData initializeWorkspace(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        if (data.projectPath == null) {
            data.projectPath = workspaceService.initializeProjectWorkspace(
                    safeValue(data.prd == null ? null : data.prd.projectName(), "GeneratedProject"), logger).toString();
        }
        return data;
    }

    /**
     * 将完整项目状态同步到唯一工作区，供编译器和测试工具连续验证同一份候选代码。
     */
    public SoftwareStudioWorkflowData persistWholeProject(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        if (data.projectPath == null) {
            initializeWorkspace(data, logger);
        }
        workspaceService.writeSourceFilesToDisk(Path.of(data.projectPath), data.codes, logger);
        logger.accept("6.5. Persisted complete generated project to the shared workspace.");
        return data;
    }

    /**
     * 将当前已完成的规划阶段产物持久化到项目元数据目录。
     */
    public SoftwareStudioWorkflowData persistPlanningArtifacts(
            SoftwareStudioWorkflowData data,
            PlanningStage stage,
            Consumer<String> logger) {
        planningArtifactPersistenceService.persistStage(data, stage, logger);
        return data;
    }

    /**
     * 工作流结束前保存可独立查看的运行摘要。
     */
    public void persistRunSummary(
            SoftwareStudioWorkflowData data,
            WorkflowRunSummary summary,
            Consumer<String> logger) {
        planningArtifactPersistenceService.persistRunSummary(data, summary, logger);
    }
}
