package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;

import static com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService.safeValue;

/**
 * 负责执行项目持久化节点，在统一契约校验后将生成项目写入本地工作区。
 */
@Service
public class PersistenceNodeService {

    private final WorkspaceService workspaceService;

    /**
     * 注入契约校验服务和工作区文件服务。
     */
    public PersistenceNodeService(WorkspaceService workspaceService) {
        this.workspaceService = workspaceService;
    }

    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        data.projectPath = workspaceService.saveProjectToDisk(
                safeValue(data.prd == null ? null : data.prd.projectName(), "GeneratedProject"),
                data.codes,
                logger).toString();
        logger.accept("6.5. Persisted generated project to disk.");
        return data;
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
     * 只写入当前切片、当前测试和 Profile 实际归一化过的文件。
     */
    public SoftwareStudioWorkflowData persistCurrentSlice(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        if (data.currentSlice() == null || data.projectPath == null) {
            return data;
        }
        workspaceService.writeSourceFilesToDisk(Path.of(data.projectPath), currentSliceCodes(data, true), logger);
        data.currentSlicePersisted = true;
        logger.accept("6.5. Persisted slice `" + data.currentSlice().name() + "` to the shared workspace.");
        return data;
    }

    /**
     * 在门禁前保存当前切片快照，失败时也能从工作区检查真实生产源码和测试文件。
     */
    public SoftwareStudioWorkflowData snapshotCurrentSlice(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        if (data.currentSlice() == null || data.projectPath == null) {
            return data;
        }
        workspaceService.writeSourceFilesToDisk(Path.of(data.projectPath), currentSliceCodes(data, false), logger);
        logger.accept("5.8. Saved preflight diagnostic snapshot for slice `" + data.currentSlice().name() + "`.");
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

    private List<SourceCode> currentSliceCodes(SoftwareStudioWorkflowData data, boolean includeNormalizedFiles) {
        LinkedHashSet<String> selectedPaths = new LinkedHashSet<>(data.currentSlice().ownedFiles());
        selectedPaths.addAll(data.currentSliceTestFiles);
        if (includeNormalizedFiles && data.qualityPolicyResult != null) {
            selectedPaths.addAll(data.qualityPolicyResult.normalizedFiles());
        }
        return data.codes.stream()
                .filter(code -> code != null && code.filename() != null)
                .filter(code -> selectedPaths.stream().anyMatch(path ->
                        path.replace('\\', '/').equals(code.filename().replace('\\', '/'))))
                .toList();
    }
}
