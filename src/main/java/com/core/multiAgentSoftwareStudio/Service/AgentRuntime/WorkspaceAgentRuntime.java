package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolCall;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolModelResponse;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static com.core.multiAgentSoftwareStudio.Config.AiConfig.CODER_MODEL_BEAN_NAME;

/**
 * 驱动模型在同一消息历史中自主搜索、编辑、编译和测试，直到平台接受阶段完成。
 */
@Service
public class WorkspaceAgentRuntime {
    private final ToolCallingModel model;
    private final WorkspaceToolRegistry toolRegistry;
    private final WorkspaceService workspaceService;
    private final LlmUsageMetricsService metricsService;
    private final WorkspaceAgentLimitsConfig limitsConfig;
    private final ToolCallAuditService auditService;

    public WorkspaceAgentRuntime(
            @Qualifier(CODER_MODEL_BEAN_NAME) ToolCallingModel model,
            WorkspaceToolRegistry toolRegistry,
            WorkspaceService workspaceService,
            LlmUsageMetricsService metricsService,
            WorkspaceAgentLimitsConfig limitsConfig,
            ToolCallAuditService auditService) {
        this.model = model;
        this.toolRegistry = toolRegistry;
        this.workspaceService = workspaceService;
        this.metricsService = metricsService;
        this.limitsConfig = limitsConfig;
        this.auditService = auditService;
    }

    /**
     * 运行一次工具会话并返回是否完成；初始开发保留失败候选，其余阶段失败时事务回滚。
     */
    public boolean run(
            SoftwareStudioWorkflowData data,
            WorkspaceAgentMode mode,
            Set<String> allowedWritePaths,
            String objective,
            Consumer<String> logger) {
        return runDetailed(data, mode, allowedWritePaths, objective, logger).completed();
    }

    /**
     * 执行工具会话并返回结构化停止证据；初始开发和测试编写保留可交接候选，修复会话仍事务回滚。
     */
    public WorkspaceAgentRunResult runDetailed(
            SoftwareStudioWorkflowData data,
            WorkspaceAgentMode mode,
            Set<String> allowedWritePaths,
            String objective,
            Consumer<String> logger) {
        if (data.projectPath == null || data.projectPath.isBlank()) {
            throw new IllegalStateException("Tool Agent requires an initialized project workspace");
        }
        Path root = Path.of(data.projectPath).toAbsolutePath().normalize();
        WorkspaceAgentSession session = new WorkspaceAgentSession(
                root,
                mode,
                data,
                logger,
                toolRegistry.snapshotWorkspace(root),
                normalizePaths(allowedWritePaths));
        String systemPrompt = systemPrompt(mode);
        session.messages().add(ToolChatMessage.system(systemPrompt));
        session.messages().add(ToolChatMessage.user(objective));
        int maxModelTurns = maxModelTurns(mode);
        int maxToolCalls = maxToolCalls(mode);
        ToolCallAuditSession auditSession = auditService.startSession(
                session, systemPrompt, objective, maxModelTurns, maxToolCalls);
        String lastToolName = "";
        String lastToolResult = "";

        try {
            for (int turn = 1; maxModelTurns <= 0 || turn <= maxModelTurns; turn++) {
                logger.accept("   Tool model turn " + turn + "/" + limitLabel(maxModelTurns) + ".");
                session.incrementModelTurns();
                auditService.recordModelTurn(auditSession, session, turn);
                ToolModelResponse response = metricsService.withCallName(
                        "Workspace" + mode.name() + "Agent",
                        () -> model.generateWithTools(session.messages(), toolRegistry.definitions()));
                auditService.recordModelResponse(auditSession, session, response);
                if (response.message() == null) {
                    return finishFailure(session, auditSession, WorkspaceAgentStopReason.MODEL_RETURNED_NO_MESSAGE,
                            lastToolName, lastToolResult, "Model returned no assistant message");
                }
                session.messages().add(response.message());
                if (!response.hasToolCalls()) {
                    session.messages().add(ToolChatMessage.user(
                            "Do not claim completion in text. Use tools to inspect and verify the workspace, then call complete_stage."));
                    continue;
                }

                for (ToolCall call : response.message().toolCalls()) {
                    if (maxToolCalls > 0 && session.toolCalls() >= maxToolCalls) {
                        logger.accept("   Tool Agent stopped at the current session tool-call limit.");
                        return finishFailure(session, auditSession, WorkspaceAgentStopReason.TOOL_CALL_LIMIT,
                                lastToolName, lastToolResult, "Tool-call limit reached");
                    }
                    session.incrementToolCalls();
                    logger.accept("   Tool call " + session.toolCalls() + "/" + limitLabel(maxToolCalls)
                            + ": " + call.name());
                    auditService.recordToolRequested(auditSession, session, call);
                    long toolStarted = System.nanoTime();
                    String result = toolRegistry.execute(call.name(), call.arguments(), session);
                    auditService.recordToolCompleted(
                            auditSession,
                            session,
                            call,
                            result,
                            (System.nanoTime() - toolStarted) / 1_000_000L);
                    lastToolName = call.name();
                    lastToolResult = result;
                    session.messages().add(ToolChatMessage.tool(call.id(), result));
                    if (session.completed()) {
                        List<String> changedFiles = synchronizeCandidate(session);
                        logger.accept("   Tool Agent completed: " + session.completionSummary());
                        auditService.finishSession(
                                auditSession,
                                session,
                                WorkspaceAgentStopReason.COMPLETED,
                                changedFiles,
                                session.completionSummary());
                        return new WorkspaceAgentRunResult(
                                true,
                                WorkspaceAgentStopReason.COMPLETED,
                                session.modelTurns(),
                                session.toolCalls(),
                                changedFiles,
                                lastToolName,
                                lastToolResult,
                                session.completionSummary());
                    }
                    if (session.blocked()) {
                        return finishFailure(session, auditSession, WorkspaceAgentStopReason.REPORTED_BLOCKER,
                                lastToolName, lastToolResult, session.completionSummary());
                    }
                }
            }
        } catch (RuntimeException e) {
            logger.accept("   Tool Agent stopped because the model or runtime failed: " + e.getMessage());
            return finishFailure(session, auditSession, WorkspaceAgentStopReason.MODEL_OR_RUNTIME_ERROR,
                    lastToolName, lastToolResult, e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        logger.accept("   Tool Agent stopped without completing the stage"
                + (maxModelTurns > 0 ? " within " + maxModelTurns + " model turns." : "."));
        return finishFailure(session, auditSession, WorkspaceAgentStopReason.MODEL_TURN_LIMIT,
                lastToolName, lastToolResult, "Model-turn limit reached");
    }

    /**
     * 初始开发和测试编写属于可增量交接阶段，未完成时也保存磁盘检查点；前端增强及修复会话失败时回滚。
     */
    private WorkspaceAgentRunResult finishFailure(
            WorkspaceAgentSession session,
            ToolCallAuditSession auditSession,
            WorkspaceAgentStopReason reason,
            String lastToolName,
            String lastToolResult,
            String message) {
        List<String> changedFiles = toolRegistry.actualChangedFiles(session);
        if (session.mode() == WorkspaceAgentMode.IMPLEMENT || session.mode() == WorkspaceAgentMode.TEST) {
            synchronizeCandidate(session);
            session.logger().accept(session.mode() == WorkspaceAgentMode.IMPLEMENT
                    ? "   Initial development candidate preserved for outer compile/repair handoff."
                    : "   Test-author checkpoint preserved for outer test/repair handoff.");
        } else {
            toolRegistry.restoreBaseline(session);
        }
        auditService.finishSession(auditSession, session, reason, changedFiles, message);
        return new WorkspaceAgentRunResult(
                false,
                reason,
                session.modelTurns(),
                session.toolCalls(),
                changedFiles,
                lastToolName,
                lastToolResult,
                message);
    }

    private List<String> synchronizeCandidate(WorkspaceAgentSession session) {
        List<String> changedFiles = toolRegistry.actualChangedFiles(session);
        session.workflowData().codes = workspaceService.loadProjectFromDisk(
                session.projectRoot(), session.logger());
        session.workflowData().repairCandidateChangedFiles = changedFiles;
        return changedFiles;
    }

    private int maxModelTurns(WorkspaceAgentMode mode) {
        return limitsConfig.forMode(mode).getMaxModelTurns();
    }

    private int maxToolCalls(WorkspaceAgentMode mode) {
        return limitsConfig.forMode(mode).getMaxToolCalls();
    }

    private String limitLabel(int limit) {
        return limit <= 0 ? "unlimited" : Integer.toString(limit);
    }

    private Set<String> normalizePaths(Set<String> paths) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (paths != null) {
            paths.stream().filter(java.util.Objects::nonNull)
                    .map(path -> path.replace('\\', '/'))
                    .forEach(normalized::add);
        }
        return normalized;
    }

    private String systemPrompt(WorkspaceAgentMode mode) {
        return """
                You are a repository coding agent operating on a real isolated candidate workspace.
                Your mode is %s.

                Work by calling tools. Never invent file contents, types, signatures, test results, or command output.
                Start with list_files/search_code/read_files. Before editing a file, read it and use its exact SHA-256.
                Apply the smallest coherent multi-file edit with apply_edits. After every edit set, run the required
                compile or tests and use the newest result as authoritative. Use get_current_diff when unsure what changed.
                Do not weaken behavioral tests, delete required functionality, add placeholder code, or edit outside scope.
                Do not output source code in normal assistant text. Only complete by calling complete_stage after the latest
                edits have passed the required real tools. If evidence is insufficient, call report_blocker.
                """.formatted(mode.name());
    }
}
