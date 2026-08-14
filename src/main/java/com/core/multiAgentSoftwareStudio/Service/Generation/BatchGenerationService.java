package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentMode;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRunResult;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRuntime;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.function.Consumer;

/**
 * 让开发 Agent 在真实项目目录中完成整套生产代码，并以真实编译结果作为完成条件。
 */
@Service
public class BatchGenerationService {

    private final WorkspaceAgentRuntime workspaceAgentRuntime;

    public BatchGenerationService(WorkspaceAgentRuntime workspaceAgentRuntime) {
        this.workspaceAgentRuntime = workspaceAgentRuntime;
    }

    /**
     * 一次性实现完整生产代码，避免在依赖尚不完整的中间批次上进行无效编译。
     */
    public WorkspaceAgentRunResult generateProject(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        logger.accept("4. Tool-driven developer is implementing the complete production project.");
        String objective = """
                Implement the complete requested project in the current workspace.

                Product requirements:
                %s

                Architecture and file responsibilities:
                %s

                Planned delivery batches:
                %s

                Required cross-layer contract:
                %s

                Inspect the real workspace before editing. Create or update all required production files, including
                build configuration and application bootstrap. Keep controller, service, persistence, DTO, template,
                JavaScript and CSS contracts consistent. Do not create tests in this stage. Run compile_main after the
                latest edit and only call complete_stage when the complete production project compiles.
                """.formatted(data.prd, data.structure, data.generationPlan, data.contract);

        WorkspaceAgentRunResult result = workspaceAgentRuntime.runDetailed(
                data,
                WorkspaceAgentMode.IMPLEMENT,
                Set.of(),
                objective,
                logger);
        if (!result.completed()) {
            String evidence = "Initial development stopped: reason=" + result.stopReason()
                    + ", modelTurns=" + result.modelTurns()
                    + ", toolCalls=" + result.toolCalls()
                    + ", changedFiles=" + result.changedFiles().size()
                    + ", lastTool=" + result.lastToolName();
            data.validationWarnings.add(evidence);
            logger.accept("   " + evidence);
            if (!result.lastToolResult().isBlank()) {
                logger.accept("   Last tool result: " + result.lastToolResult());
            }
        }
        return result;
    }
}
