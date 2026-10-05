package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolCall;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolModelResponse;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workflow.DevelopmentMilestoneService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.net.http.HttpTimeoutException;
import java.util.ArrayList;
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
    private final DevelopmentMilestoneService milestoneService;

    public WorkspaceAgentRuntime(
            @Qualifier(CODER_MODEL_BEAN_NAME) ToolCallingModel model,
            WorkspaceToolRegistry toolRegistry,
            WorkspaceService workspaceService,
            LlmUsageMetricsService metricsService,
            WorkspaceAgentLimitsConfig limitsConfig,
            ToolCallAuditService auditService,
            DevelopmentMilestoneService milestoneService) {
        this.model = model;
        this.toolRegistry = toolRegistry;
        this.workspaceService = workspaceService;
        this.metricsService = metricsService;
        this.limitsConfig = limitsConfig;
        this.auditService = auditService;
        this.milestoneService = milestoneService;
    }

    /**
     * 运行一次工具会话并返回是否完成；未完成候选是否有价值由外层真实验证决定。
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
     * 执行工具会话并返回结构化停止证据；除可选前端增强外，未完成的磁盘候选也可交给外层验证。
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
        boolean restoredContext = false;
        if (mode == WorkspaceAgentMode.DEVELOPMENT && session.modelTurns() > 0) {
            try {
                int restored = WorkspaceDevelopmentContext.restore(session);
                restoredContext = restored > 0 || !session.verificationFailures().isEmpty()
                        || session.checkpointObservation() != null;
                logger.accept("   Restored development context messages: " + restored + "; cumulative budget retained.");
            } catch (Exception e) {
                logger.accept("   Development context unavailable; current goal and disk source retained: " + e.getMessage());
            }
        }
        int maxModelTurns = maxModelTurns(mode);
        int maxToolCalls = maxToolCalls(mode);
        ToolCallAuditSession auditSession = auditService.startSession(
                session, systemPrompt, objective, maxModelTurns, maxToolCalls);
        String lastToolName = "";
        String lastToolResult = "";
        WorkspaceAgentStagnationTracker stagnationTracker =
                new WorkspaceAgentStagnationTracker(limitsConfig.getStagnation());
        WorkspaceAgentContextCompactor contextCompactor =
                new WorkspaceAgentContextCompactor(limitsConfig.getContext());
        boolean retriedTimeout = false;

        try {
            // 续接诊断来自同源码持久化状态，不能等到窗口超阈值或再次验收才首次送达。
            if (restoredContext) recordCompaction(contextCompactor.compactForResume(session,
                    maxModelTurns > 0 ? Math.max(0, maxModelTurns - session.modelTurns()) : -1), auditSession, session);
            for (int turn = session.modelTurns() + 1; maxModelTurns <= 0 || turn <= maxModelTurns; turn++) {
                logger.accept("   Tool model turn " + turn + "/" + limitLabel(maxModelTurns) + ".");
                boolean closing = maxModelTurns > 0 && turn == Math.max(1,
                        maxModelTurns - Math.min(8, Math.max(3, maxModelTurns / 6)));
                if (mode == WorkspaceAgentMode.DEVELOPMENT
                        && session.validationDue(limitsConfig.getValidation(), closing)) {
                    validateCandidate(session, auditSession);
                }
                // 在预算约三分之二处先验证一次，给真实错误留下足够修复轮次。
                if (mode == WorkspaceAgentMode.DEVELOPMENT && maxModelTurns >= 12
                        && turn == Math.max(1, maxModelTurns * 2 / 3)) {
                    session.messages().add(ToolChatMessage.user(developmentVerificationPrompt()));
                }
                // 全局预算将尽时提醒先验证当前改动，避免最后几轮留下未编译的新文件。
                if (mode == WorkspaceAgentMode.DEVELOPMENT && maxModelTurns > 0
                        && turn == Math.max(1, maxModelTurns - Math.min(8, Math.max(3, maxModelTurns / 6)))) {
                    session.messages().add(ToolChatMessage.user(developmentClosingPrompt(
                            maxModelTurns - turn + 1)));
                }
                // 包括无工具回复后的续跑，统一在下一次请求前按合法工具边界整理历史。
                recordCompaction(contextCompactor.compact(session, lastToolName, lastToolResult,
                        maxModelTurns > 0 ? maxModelTurns - session.modelTurns() : -1),
                        auditSession, session);
                session.incrementModelTurns();
                if (mode == WorkspaceAgentMode.DEVELOPMENT) milestoneService.persist(data);
                auditService.recordModelTurn(auditSession, session, turn);
                ToolModelResponse response;
                long modelCallStarted = System.nanoTime();
                try {
                    response = metricsService.withCallName(
                            "Workspace" + mode.name() + "Agent",
                            () -> model.generateWithTools(session.messages(), toolRegistry.definitions(mode)));
                    retriedTimeout = false;
                } catch (RuntimeException e) {
                    auditService.recordModelFailure(auditSession, session, isTimeout(e),
                            (System.nanoTime() - modelCallStarted) / 1_000_000L);
                    // 只有超时才重试一次；失败调用仍计入全局轮次，且尚未执行任何工具。
                    if (mode == WorkspaceAgentMode.DEVELOPMENT && !retriedTimeout
                            && isTimeout(e) && (maxModelTurns <= 0 || turn < maxModelTurns)) {
                        retriedTimeout = true;
                        recordCompaction(contextCompactor.compactForRetry(session, lastToolName, lastToolResult,
                                maxModelTurns > 0 ? maxModelTurns - session.modelTurns() : -1),
                                auditSession, session);
                        logger.accept("   Tool model request timed out; retrying once within the remaining model-turn budget.");
                        continue;
                    }
                    throw e;
                }
                auditService.recordModelResponse(auditSession, session, response,
                        (System.nanoTime() - modelCallStarted) / 1_000_000L);
                if (response.message() == null) {
                    return finishFailure(session, auditSession, WorkspaceAgentStopReason.MODEL_RETURNED_NO_MESSAGE,
                            lastToolName, lastToolResult, "Model returned no assistant message");
                }
                session.messages().add(response.message());
                if (!response.hasToolCalls()) {
                    if (mode == WorkspaceAgentMode.DEVELOPMENT
                            && session.validationDue(limitsConfig.getValidation(), true)) {
                        validateCandidate(session, auditSession);
                    }
                    session.messages().add(ToolChatMessage.user(mode == WorkspaceAgentMode.DEVELOPMENT
                            ? developmentContinuationPrompt()
                            : "Do not claim completion in text. Use tools to inspect and verify the workspace, then call complete_stage."));
                    continue;
                }

                String runtimeGuidance = "";
                for (ToolCall call : response.message().toolCalls()) {
                    if (maxToolCalls > 0 && session.toolCalls() >= maxToolCalls) {
                        logger.accept("   Tool Agent stopped at the current session tool-call limit.");
                        return finishFailure(session, auditSession, WorkspaceAgentStopReason.TOOL_CALL_LIMIT,
                                lastToolName, lastToolResult, "Tool-call limit reached");
                    }
                    session.incrementToolCalls();
                    if (mode == WorkspaceAgentMode.DEVELOPMENT) milestoneService.persist(data);
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
                    WorkspaceAgentStagnationTracker.Decision stagnation =
                            stagnationTracker.observe(call, result, session);
                    if (!stagnation.guidance().isBlank()) {
                        runtimeGuidance = stagnation.guidance();
                    }
                    if (stagnation.stop()) {
                        logger.accept("   Tool Agent stopped after detecting stagnation: " + stagnation.reason() + ".");
                        return finishFailure(session, auditSession, WorkspaceAgentStopReason.STAGNATED,
                                lastToolName, lastToolResult, stagnation.reason());
                    }
                }
                if (!runtimeGuidance.isBlank()) {
                    // 一个响应的全部 tool_call_id 配对完成后再交付提示，保持兼容接口消息序列合法。
                    session.messages().add(ToolChatMessage.user(runtimeGuidance));
                    auditService.recordRuntimeGuidance(auditSession, session, runtimeGuidance);
                    logger.accept("   Repeated verification failure: diagnostic guidance added before the next attempt.");
                }

            }
        } catch (RuntimeException e) {
            logger.accept("   Tool Agent stopped because the model or runtime failed: " + e.getMessage());
            return finishFailure(session, auditSession, WorkspaceAgentStopReason.MODEL_OR_RUNTIME_ERROR,
                    lastToolName, lastToolResult, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            toolRegistry.closeSession(session);
            if (mode == WorkspaceAgentMode.DEVELOPMENT) {
                try {
                    contextCompactor.compact(session, lastToolName, lastToolResult,
                            Math.max(0, maxModelTurns - session.modelTurns()));
                    WorkspaceDevelopmentContext.save(session, toolRegistry.snapshotWorkspace(root));
                } catch (Exception e) {
                    logger.accept("   Unable to save development context: " + e.getMessage());
                }
            }
        }

        logger.accept("   Tool Agent stopped without completing the stage"
                + (maxModelTurns > 0 ? " within " + maxModelTurns + " model turns." : "."));
        return finishFailure(session, auditSession, WorkspaceAgentStopReason.MODEL_TURN_LIMIT,
                lastToolName, lastToolResult, "Model-turn limit reached");
    }

    /** 工具响应配对后或收到结束声明时，将真实平台验证交回当前会话，不伪造工具调用。 */
    private void validateCandidate(WorkspaceAgentSession session, ToolCallAuditSession auditSession) {
        session.logger().accept("   Platform progress validation before the next model request.");
        long started = System.nanoTime();
        String feedback = toolRegistry.validateProgress(session);
        if (feedback != null && !feedback.isBlank()) {
            session.messages().add(ToolChatMessage.user(feedback));
            auditService.recordRuntimeGuidance(auditSession, session,
                    "Automatic validation durationMillis=" + (System.nanoTime() - started) / 1_000_000L
                            + "; " + feedback);
        }
    }

    /** 记录实际请求前的体积变化；必要目标超出预算时明确报告，不截断需求来制造达标。 */
    private void recordCompaction(WorkspaceAgentContextCompactor.Result result,
                                  ToolCallAuditSession audit, WorkspaceAgentSession session) {
        if (!result.compacted()) return;
        auditService.recordContextCompacted(audit, session, result);
        session.logger().accept("   Tool context compacted: " + result.estimatedTokensBefore()
                + " -> " + result.estimatedTokensAfter() + " estimated tokens (" + result.reason() + ").");
        if (!result.withinWorkingBudget()) {
            session.logger().accept("   Mandatory objective/evidence exceeds the working context budget; retained intact.");
        }
    }

    /** 识别兼容层包装的网络超时，避免把一般 HTTP 或代码错误当作可重试故障。 */
    private boolean isTimeout(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof HttpTimeoutException) return true;
        }
        return false;
    }

    /**
     * 未完成不等于无价值：开发、测试和修复均同步磁盘候选，只有可选前端增强自动恢复基线。
     */
    private WorkspaceAgentRunResult finishFailure(
            WorkspaceAgentSession session,
            ToolCallAuditSession auditSession,
            WorkspaceAgentStopReason reason,
            String lastToolName,
            String lastToolResult,
            String message) {
        List<String> changedFiles = toolRegistry.actualChangedFiles(session);
        if (preservesIncompleteCandidate(session.mode())) {
            synchronizeCandidate(session);
            session.logger().accept("   Incomplete candidate preserved for outer verification: "
                    + session.mode() + ".");
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

    private boolean preservesIncompleteCandidate(WorkspaceAgentMode mode) {
        return mode != WorkspaceAgentMode.FRONTEND_INTEGRATION;
    }

    private List<String> synchronizeCandidate(WorkspaceAgentSession session) {
        List<String> changedFiles = toolRegistry.actualChangedFiles(session);
        session.workflowData().codes = workspaceService.loadProjectFromDisk(
                session.projectRoot(), session.logger());
        // 修复回滚会清空候选列表；工具层返回的 stream.toList() 不可修改。
        session.workflowData().repairCandidateChangedFiles = new ArrayList<>(changedFiles);
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

    /** 无工具回复后重申完整项目验收入口，不强制逐任务推进。 */
    private String developmentContinuationPrompt() {
        return "Continue editing and verifying. To request completion, call submit_checkpoint with scope=final.";
    }

    /** 在剩余轮次有限时提醒验证已有改动并请求全项目验收。 */
    private String developmentClosingPrompt(int remainingTurns) {
        return "About " + remainingTurns + " model turns remain in the global budget. Prioritize compiling "
                + "and running all existing tests, fixing their failures, then verifying the project with submit_checkpoint(scope=final)"
                + ". Do not add unrelated future files or tests that cannot be verified before the budget ends.";
    }

    /** 在尚有修复预算时要求获得第一份全套测试反馈。 */
    private String developmentVerificationPrompt() {
        return "The development budget is about two thirds used. Run a full compile and all current tests now "
                + "(prefer submit_checkpoint(scope=progress)); inspect every failure before adding more files. "
                + "Use the remaining turns to fix the implementation or invalid test fixtures, rerun tests, "
                + "and submit the required final checkpoint.";
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
        if (mode == WorkspaceAgentMode.DEVELOPMENT) {
            return """
                    You are DevelopmentAgent, continuing one project in its real workspace.
                    You may edit production code, frontend files, development tests, pom.xml and README.md.
                    Keep every required user behavior and the public contract. Never edit platform acceptance assets.
                    Use list_files/search_code/read_files to inspect the workspace. Use apply_patch for related-file
                    diffs, or apply_edits for exact replacements/new files without patch line prefixes. Use bash for builds,
                    tests and diagnosis. If a patch reports a context mismatch, read the current file and rebase
                    the patch; do not switch to repeated one-file shell writes.
                    After a coherent production/test edit, compile and run the relevant tests before further refactoring.
                    Bash runs in a restricted container; it can change project files, so inspect current disk state after it.
                    Use submit_checkpoint(scope=progress) for full compile and all current tests while work continues.
                    A progress pass is feedback, not project completion. Use submit_checkpoint(scope=final) only after
                    implementation, tests and README are ready. Final failure means repair and submit again.
                    Normal assistant text cannot complete the task. Report an unresolvable task with report_blocker.
                    If the original request conflicts with a planning-derived public contract, report_blocker with
                    kind=planning_conflict and specific evidence; continue after the tool returns a decision.
                    Do not weaken valid tests, remove required behavior or claim an unverified result.
                    When writing behavior tests, keep success-case fixtures within declared validation constraints;
                    use invalid values only when asserting rejection. Assert the observable behavior being tested,
                    including ordering, persistence changes, and HTTP responses; a request without assertions is not a test.
                    Do not assume generated database IDs start at a fixed value; use the ID returned by persistence or HTTP.
                    Tests sharing an application context must isolate mutable state with controlled fixtures or a fresh context.
                    Do not assume an initially empty store or exact record count unless each test establishes that state.
                    For rejected mutating requests, verify both the unchanged stored state and a subsequent operation;
                    a correct error response alone does not prove that hidden state remained valid.
                    """;
        }
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
