package com.core.multiAgentSoftwareStudio.Model.Tool;

/**
 * 保存模型返回的单次函数调用，arguments 保留供应商返回的原始 JSON 字符串。
 */
public record ToolCall(
        String id,
        String name,
        String arguments
) {
}
