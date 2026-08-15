package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

/**
 * 标识工具会话结束的真实原因，避免所有失败都退化成一个布尔值。
 */
public enum WorkspaceAgentStopReason {
    COMPLETED,
    MODEL_TURN_LIMIT,
    TOOL_CALL_LIMIT,
    STAGNATED,
    MODEL_RETURNED_NO_MESSAGE,
    REPORTED_BLOCKER,
    MODEL_OR_RUNTIME_ERROR
}
