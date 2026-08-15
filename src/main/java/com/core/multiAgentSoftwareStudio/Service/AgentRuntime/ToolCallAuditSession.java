package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import java.nio.file.Path;

/**
 * 保存一次工具会话的审计标识和日志路径。
 */
public record ToolCallAuditSession(
        String sessionId,
        Path logPath,
        boolean enabled
) {
    public static ToolCallAuditSession disabled() {
        return new ToolCallAuditSession("", null, false);
    }
}
