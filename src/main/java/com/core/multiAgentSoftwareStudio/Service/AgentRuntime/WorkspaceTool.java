package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Model.Tool.ToolDefinition;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 统一工具定义与执行入口，所有工具都只能通过受控会话访问候选项目。
 */
public interface WorkspaceTool {
    ToolDefinition definition();

    String execute(JsonNode arguments, WorkspaceAgentSession session);
}
