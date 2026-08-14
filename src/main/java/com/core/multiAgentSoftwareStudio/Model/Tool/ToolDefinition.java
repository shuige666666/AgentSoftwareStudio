package com.core.multiAgentSoftwareStudio.Model.Tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 描述一个可以暴露给模型的受控工具及其 JSON Schema 参数。
 */
public record ToolDefinition(
        String name,
        String description,
        JsonNode parameters
) {
}
