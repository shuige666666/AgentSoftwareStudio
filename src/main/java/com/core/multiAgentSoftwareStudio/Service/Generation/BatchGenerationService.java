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
                ? milestoneObjective(data) : """
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

    /** 多能力任务只注入业务目标和当前进度；详细设计由 Agent 按需读取磁盘资料。 */
    private String milestoneObjective(SoftwareStudioWorkflowData data) {
        StringBuilder objective = new StringBuilder("""
                Implement the requested project through the ordered business milestones below.
                Work in one continuous development session with one global model/tool budget.
                Inspect relevant source files before editing. The current milestone context is included below.
                Each milestone may change multiple layers and must keep all earlier required behavior.
                Implement and test the current milestone first. Defer later endpoints, pages and tests until
                its progress checkpoint passes; create only shared types needed to keep the current code building.
                Group related source changes into multi-file patches where practical, then run focused tests.
                Do not spend the remaining turns writing unrelated future files or polishing the whole project
                before requesting the current milestone checkpoint.
                After implementing and testing the current milestone, call submit_checkpoint with
                scope=progress and its milestoneId. A passing response gives the next milestone.
                After every milestone is accepted, call submit_checkpoint with scope=final.
                Do not claim a capability is complete from a build alone; add meaningful behavior tests.

                Original user request:
                """).append(data.userRequest)
                .append("\n\nBusiness milestones (future items are context, not work for this checkpoint):\n");
        data.milestoneState.tasks().forEach(task -> objective.append("- ").append(task.id())
                .append(": ").append(task.goal())
                .append(task.accepted() ? " [accepted at " + task.sourceHash() + "]" : " [pending]")
                .append('\n'));
        var current = data.milestoneState.current();
        if (current != null) {
            objective.append("\nCurrent milestone: ").append(current.id()).append('\n');
            objective.append("Current acceptance criteria: ").append(current.acceptanceCriteria()).append('\n');
            data.sliceDeliveryPlan.slices().stream().filter(slice -> slice.id().equals(current.id()))
                    .findFirst().ifPresent(slice -> objective.append("Current milestone contract:\n")
                            .append(slice.contract()).append('\n'));
        }
        if (!data.milestoneState.lastFailure().isBlank()) {
            objective.append("Last failure: ").append(data.milestoneState.lastFailure()).append('\n');
        }
        return objective.toString();
    }
}
