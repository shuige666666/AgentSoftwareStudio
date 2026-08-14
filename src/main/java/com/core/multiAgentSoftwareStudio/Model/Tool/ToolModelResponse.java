package com.core.multiAgentSoftwareStudio.Model.Tool;

/**
 * 保存一次原生工具模型响应以及供应商返回的结束原因。
 */
public record ToolModelResponse(
        ToolChatMessage message,
        String finishReason
) {
    public boolean hasToolCalls() {
        return message != null && message.toolCalls() != null && !message.toolCalls().isEmpty();
    }
}
