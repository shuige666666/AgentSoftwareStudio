package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentMode;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRunResult;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.WorkspaceAgentRuntime;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.function.Consumer;

/**
 * 让开发 Agent 在同一工作区会话中完成实现、测试、修复及正式验收。
 */
@Service
public class BatchGenerationService {

    private final WorkspaceAgentRuntime workspaceAgentRuntime;

    public BatchGenerationService(WorkspaceAgentRuntime workspaceAgentRuntime) {
        this.workspaceAgentRuntime = workspaceAgentRuntime;
    }

    /**
     * 交给连续开发会话处理跨层文件，并由最终 checkpoint 决定是否完成。
     */
    public WorkspaceAgentRunResult generateProject(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        logger.accept("4. DevelopmentAgent is implementing and verifying the complete project.");
        String objective = data.milestoneState != null && data.milestoneState.active()
                ? DevelopmentMilestoneObjective.render(data) : """
                Implement and verify the complete requested project in the current workspace.

                Original user request:
                %s

                Product requirements:
                %s

                Architecture and file responsibilities:
                %s

                Required cross-layer contract:
                %s

                Inspect the real workspace and its already compiling scaffold before editing. Keep the existing
                build configuration and application bootstrap unless a concrete business need requires a change.
                Create or update business code, frontend, meaningful behavior tests and README in this same session.
                Keep controller, service, persistence, DTO, template, JavaScript and CSS contracts consistent.
                Use bash for focused diagnosis; submit_checkpoint(scope=progress) for full build and all existing tests.
                Resolve failures in this session, then submit_checkpoint(scope=final) for completion.
                """.formatted(data.userRequest, data.prd, data.structure, data.contract);

        WorkspaceAgentRunResult result = workspaceAgentRuntime.runDetailed(
                data,
                WorkspaceAgentMode.DEVELOPMENT,
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
