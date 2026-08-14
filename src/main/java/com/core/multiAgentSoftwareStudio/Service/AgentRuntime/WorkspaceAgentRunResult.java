package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import java.util.List;

/**
 * 返回工具会话的完成状态、停止原因和最后工具证据，供工作流决定回滚还是接力修复。
 */
public record WorkspaceAgentRunResult(
        boolean completed,
        WorkspaceAgentStopReason stopReason,
        int modelTurns,
        int toolCalls,
        List<String> changedFiles,
        String lastToolName,
        String lastToolResult,
        String message
) {
    public WorkspaceAgentRunResult {
        changedFiles = changedFiles == null ? List.of() : List.copyOf(changedFiles);
        lastToolName = lastToolName == null ? "" : lastToolName;
        lastToolResult = lastToolResult == null ? "" : lastToolResult;
        message = message == null ? "" : message;
    }
}
