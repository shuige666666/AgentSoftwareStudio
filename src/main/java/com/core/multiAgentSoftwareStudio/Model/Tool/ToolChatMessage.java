package com.core.multiAgentSoftwareStudio.Model.Tool;

import java.util.List;

/**
 * 表示工具会话中的结构化消息，完整保留 tool_call_id 和模型调用指令。
 */
public record ToolChatMessage(
        String role,
        String content,
        String toolCallId,
        List<ToolCall> toolCalls
) {
    public ToolChatMessage {
        role = role == null ? "user" : role;
        content = content == null ? "" : content;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }

    public static ToolChatMessage system(String content) {
        return new ToolChatMessage("system", content, null, List.of());
    }

    public static ToolChatMessage user(String content) {
        return new ToolChatMessage("user", content, null, List.of());
    }

    public static ToolChatMessage assistant(String content, List<ToolCall> calls) {
        return new ToolChatMessage("assistant", content, null, calls);
    }

    public static ToolChatMessage tool(String callId, String content) {
        return new ToolChatMessage("tool", content, callId, List.of());
    }
}
