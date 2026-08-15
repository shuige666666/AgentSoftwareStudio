package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * 确定性压缩旧工具历史，同时保留原始目标以及最近完整的 assistant/tool 配对消息。
 */
final class WorkspaceAgentContextCompactor {

    private final WorkspaceAgentLimitsConfig.ContextPolicy policy;

    WorkspaceAgentContextCompactor(WorkspaceAgentLimitsConfig.ContextPolicy policy) {
        this.policy = policy;
    }

    /**
     * 在一个模型响应的全部工具调用完成后压缩，防止拆断同一响应中的 tool_call_id 配对。
     */
    Result compact(
            WorkspaceAgentSession session,
            String lastToolName,
            String lastToolResult) {
        List<ToolChatMessage> messages = session.messages();
        if (session.modelTurns() < policy.getCompactAfterModelTurns() || messages.size() <= 3) {
            return Result.unchanged(messages.size());
        }

        List<Integer> assistantIndexes = new ArrayList<>();
        for (int index = 0; index < messages.size(); index++) {
            if ("assistant".equals(messages.get(index).role())) {
                assistantIndexes.add(index);
            }
        }
        if (assistantIndexes.size() <= policy.getRetainedModelTurns()) {
            return Result.unchanged(messages.size());
        }

        int retainedStart = assistantIndexes.get(assistantIndexes.size() - policy.getRetainedModelTurns());
        int before = messages.size();
        List<ToolChatMessage> compacted = new ArrayList<>();
        compacted.add(messages.get(0));
        compacted.add(messages.get(1));
        compacted.add(ToolChatMessage.user(summary(session, lastToolName, lastToolResult)));
        compacted.addAll(messages.subList(retainedStart, messages.size()));
        messages.clear();
        messages.addAll(compacted);
        return new Result(true, before, messages.size());
    }

    private String summary(WorkspaceAgentSession session, String lastToolName, String lastToolResult) {
        return """
                Platform context checkpoint (authoritative; older conversational history was compacted):
                - mode: %s
                - current changed files: %s
                - workspace write version: %d
                - latest compile current: %s
                - latest tests current: %s
                - latest contract validation current: %s
                - latest tool: %s
                - latest real tool result:
                %s

                Re-read the current files before editing. Continue from this evidence and do not assume omitted history.
                """.formatted(
                session.mode(),
                session.changedFiles(),
                session.writeVersion(),
                session.compileCurrent(),
                session.testsCurrent(),
                session.contractCurrent(),
                lastToolName == null ? "" : lastToolName,
                bounded(lastToolResult));
    }

    private String bounded(String value) {
        String safe = value == null ? "" : value;
        int limit = policy.getMaxLatestToolResultChars();
        if (safe.length() <= limit) {
            return safe;
        }
        int head = Math.max(1, limit / 4);
        int tail = Math.max(1, limit - head - 48);
        return safe.substring(0, head) + "\n... compacted; newest tail follows ...\n"
                + safe.substring(safe.length() - tail);
    }

    record Result(boolean compacted, int messagesBefore, int messagesAfter) {
        private static Result unchanged(int size) { return new Result(false, size, size); }
    }
}
