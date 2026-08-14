package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

/**
 * 区分工具会话的业务职责，用于动态限制可写目录和完成条件。
 */
public enum WorkspaceAgentMode {
    IMPLEMENT,
    TEST,
    FRONTEND_INTEGRATION,
    REPAIR_IMPLEMENTATION,
    REPAIR_TEST,
    REPAIR_CONTRACT
}
