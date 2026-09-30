package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.core.multiAgentSoftwareStudio.Service.Generation.DevelopmentMilestoneObjective;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 按工作预算保留必要目标和真实证据，完整源码与工具审计仍以磁盘为准。 */
final class WorkspaceAgentContextCompactor {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final WorkspaceAgentLimitsConfig.ContextPolicy policy;

    WorkspaceAgentContextCompactor(WorkspaceAgentLimitsConfig.ContextPolicy policy) {
        this.policy = policy;
    }

    /** 只在全部工具响应配对完成后调用；正常压缩同时考虑窗口阈值与工作预算。 */
    Result compact(WorkspaceAgentSession session, String lastToolName, String lastToolResult,
                   int remainingModelTurns) {
        return compact(session, lastToolName, lastToolResult, remainingModelTurns, false);
    }

    /** 超时没有产生新工具调用，先移出冗余历史再在原全局预算内重试。 */
    Result compactForRetry(WorkspaceAgentSession session, String lastToolName, String lastToolResult,
                           int remainingModelTurns) {
        return compact(session, lastToolName, lastToolResult, remainingModelTurns, true);
    }

    /** 先压缩旧版本读取，再按完整轮次移出历史；不能用截断源码伪装成准确编辑上下文。 */
    private Result compact(WorkspaceAgentSession session, String lastToolName, String lastToolResult,
                           int remainingModelTurns, boolean retry) {
        List<ToolChatMessage> messages = session.messages();
        int before = messages.size();
        long tokensBefore = estimatedTokens(messages);
        long trigger = Math.min(workingBudget(),
                (long) policy.getMaxContextTokens() * policy.getCompactAtPercent() / 100);
        if (messages.size() <= 2 || !retry && tokensBefore < trigger) {
            return Result.unchanged(before, tokensBefore, workingBudget());
        }

        List<Integer> assistantIndexes = new ArrayList<>();
        Map<String, String> toolNames = new HashMap<>();
        for (int index = 2; index < messages.size(); index++) {
            ToolChatMessage message = messages.get(index);
            if ("assistant".equals(message.role())) assistantIndexes.add(index);
            message.toolCalls().forEach(call -> toolNames.put(call.id(), call.name()));
        }
        if (assistantIndexes.isEmpty()) {
            return Result.unchanged(before, tokensBefore, workingBudget());
        }

        List<ToolChatMessage> pinned = new ArrayList<>();
        pinned.add(messages.getFirst());
        // 当前任务由持久化状态重建，首任务通过后不能重新注入旧目标。
        if (session.mode() == WorkspaceAgentMode.DEVELOPMENT
                && session.workflowData().milestoneState != null
                && session.workflowData().milestoneState.active()) {
            pinned.add(ToolChatMessage.user(DevelopmentMilestoneObjective.render(session.workflowData())));
        } else {
            pinned.add(messages.get(1));
        }
        pinned.add(ToolChatMessage.user(summary(session, lastToolName, lastToolResult, remainingModelTurns)));

        int start = assistantIndexes.get(Math.max(0, assistantIndexes.size() - policy.getRetainedModelTurns()));
        List<ToolChatMessage> recent = new ArrayList<>(messages.subList(start, messages.size()));
        omitSupersededReads(recent, toolNames);
        // 重试时即使尚未达到预算，也移出已有的大段读取，避免再次原样发送超时请求。
        if (retry) omitAllReadBodies(recent, toolNames);
        while (!recent.isEmpty() && estimatedTokens(join(pinned, recent)) > workingBudget()) {
            int next = -1;
            for (int index = 1; index < recent.size(); index++) {
                if ("assistant".equals(recent.get(index).role())) {
                    next = index;
                    break;
                }
            }
            if (next >= 0) {
                recent.subList(0, next).clear();
            } else {
                // 最近一轮读取超出预算时只留下文件元信息，要求后续按行重新读取。
                List<ToolChatMessage> reduced = new ArrayList<>(recent);
                omitAllReadBodies(reduced, toolNames);
                if (!reduced.equals(recent)) recent = reduced;
                else recent.clear(); // 大补丁等已执行轮次整体移出，不伪造部分工具参数。
            }
        }

        List<ToolChatMessage> compacted = join(pinned, recent);
        if (messages.equals(compacted)) {
            return Result.unchanged(before, tokensBefore, workingBudget());
        }
        messages.clear();
        messages.addAll(compacted);
        return new Result(true, before, messages.size(), tokensBefore, estimatedTokens(messages),
                workingBudget(), retry ? "timeout_retry" : "context_budget");
    }

    /** 从后往前保留每个文件最新一次读取，旧读取保留路径、哈希和明确的历史标记。 */
    private void omitSupersededReads(List<ToolChatMessage> recent, Map<String, String> toolNames) {
        var seen = new HashSet<String>();
        var writtenLater = new HashSet<String>();
        for (int index = recent.size() - 1; index >= 0; index--) {
            ToolChatMessage message = recent.get(index);
            if (!"tool".equals(message.role())) continue;
            try {
                ObjectNode result = (ObjectNode) MAPPER.readTree(message.content());
                // 后续 patch/Bash 已改动文件时，即使没有再次读取，旧正文也不能继续当作当前版本。
                result.path("changedFiles").forEach(path -> writtenLater.add(path.asText()));
                if (!isRead(toolNames.get(message.toolCallId()))) continue;
                boolean changed = false;
                for (var entry : result.path("files")) {
                    String path = entry.path("path").asText();
                    boolean superseded = !seen.add(path) || writtenLater.contains(path);
                    if (superseded && entry instanceof ObjectNode file && file.has("content")) {
                        omitBody(file);
                        changed = true;
                    }
                }
                if (changed) recent.set(index, ToolChatMessage.tool(message.toolCallId(), result.toString()));
            } catch (Exception ignored) {
                // 非标准或失败响应保持原文，不能把解析问题变成伪造的读取证据。
            }
        }
    }

    /** 完整源码保存在磁盘；移出正文时不提供容易被当作整文件的首尾片段。 */
    private void omitAllReadBodies(List<ToolChatMessage> messages, Map<String, String> toolNames) {
        for (int index = 0; index < messages.size(); index++) {
            ToolChatMessage message = messages.get(index);
            if (isRead(toolNames.get(message.toolCallId()))) {
                messages.set(index, ToolChatMessage.tool(message.toolCallId(), withoutReadBodies(message.content())));
            }
        }
    }

    /** 保留标准文件响应的元信息，不生成可能误用于编辑的正文片段。 */
    private String withoutReadBodies(String content) {
        try {
            ObjectNode result = (ObjectNode) MAPPER.readTree(content);
            for (var entry : result.path("files")) {
                if (entry instanceof ObjectNode file && file.has("content")) omitBody(file);
            }
            return result.toString();
        } catch (Exception ignored) {
            return content;
        }
    }

    /** 明确标记读取已经成为历史，旧哈希不能直接代表当前磁盘版本。 */
    private void omitBody(ObjectNode file) {
        file.remove("content");
        file.put("contentOmitted", true);
        file.put("historyNote", "Historical read metadata, not current editable text. Re-read the file with read_files (startLine/endLine) or read_planning.");
    }

    /** 工作预算不能大于配置的窗口；必需目标过大时保留事实并由审计明确报告。 */
    private int workingBudget() {
        return Math.min(policy.getMaxWorkingContextTokens(), policy.getMaxContextTokens());
    }

    /** 只精简已知只读工具的正文，其他执行事实保持原结果。 */
    private boolean isRead(String name) {
        return "read_files".equals(name) || "read_planning".equals(name);
    }

    /** 将必要事实与完整工具轮次组合为下一次请求的历史。 */
    private List<ToolChatMessage> join(List<ToolChatMessage> pinned, List<ToolChatMessage> recent) {
        List<ToolChatMessage> result = new ArrayList<>(pinned);
        result.addAll(recent);
        return result;
    }

    /** 两字符一 token 的保守估算用于工作预算，不代替供应商计费用量。 */
    static long estimatedTokens(List<ToolChatMessage> messages) {
        long characters = 0;
        for (ToolChatMessage message : messages) {
            characters += message.content().length() + 32L;
            for (var call : message.toolCalls()) {
                characters += call.name().length() + call.arguments().length() + 32L;
            }
        }
        return (characters + 1) / 2;
    }

    /** 目标、验证失败、当前写入版本及剩余预算始终保留，不把最新读取正文再复制一份。 */
    private String summary(WorkspaceAgentSession session, String lastToolName, String lastToolResult,
                           int remainingModelTurns) {
        String closingReminder = session.mode() == WorkspaceAgentMode.DEVELOPMENT
                && remainingModelTurns >= 0 && remainingModelTurns <= 8
                ? "- remaining model turns: " + remainingModelTurns
                        + "; fix unresolved failures, rerun tests and submit the current checkpoint.\n"
                : "- remaining model turns: " + remainingModelTurns + "\n";
        return """
                Platform context checkpoint (authoritative state; omitted tool details remain on disk):
                - mode: %s
                - current changed files: %s
                - workspace write version: %d
                - latest compile current: %s
                - latest tests current: %s
                - latest contract validation current: %s
                - unresolved verification failure from write version %d:
                %s
                - latest tool: %s
                - latest real tool result:
                %s
                %s
                Re-read current files before editing; omitted bodies and historical hashes are not current editable text.
                Use read_planning for the complete public contract and read_files line ranges for exact source.
                """.formatted(session.mode(), session.changedFiles(), session.writeVersion(),
                session.compileCurrent(), session.testsCurrent(), session.contractCurrent(),
                session.unresolvedFailureVersion(), bounded(session.unresolvedVerificationFailure()),
                lastToolName == null ? "" : lastToolName,
                bounded(isRead(lastToolName) ? withoutReadBodies(lastToolResult) : lastToolResult), closingReminder);
    }

    /** 摘要使用有界首尾证据，完整诊断仍在原工具日志中。 */
    private String bounded(String value) {
        String safe = value == null ? "" : value;
        int limit = policy.getMaxLatestToolResultChars();
        if (safe.length() <= limit) return safe;
        int head = limit / 4;
        int tail = Math.max(0, limit - head - 64);
        return safe.substring(0, head) + "\n... omitted; full evidence remains on disk ...\n"
                + safe.substring(safe.length() - tail);
    }

    record Result(boolean compacted, int messagesBefore, int messagesAfter, long estimatedTokensBefore,
                  long estimatedTokensAfter, int workingBudgetTokens, String reason) {
        boolean withinWorkingBudget() { return estimatedTokensAfter <= workingBudgetTokens; }
        private static Result unchanged(int size, long tokens, int budget) {
            return new Result(false, size, size, tokens, tokens, budget, "");
        }
    }
}
