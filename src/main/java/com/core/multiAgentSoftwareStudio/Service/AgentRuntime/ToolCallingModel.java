package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolDefinition;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolModelResponse;

import java.util.List;

/**
 * 定义供应商无关的原生工具调用接口，避免把工具消息压缩成普通字符串。
 */
public interface ToolCallingModel {
    ToolModelResponse generateWithTools(
            List<ToolChatMessage> messages,
            List<ToolDefinition> tools);
}
