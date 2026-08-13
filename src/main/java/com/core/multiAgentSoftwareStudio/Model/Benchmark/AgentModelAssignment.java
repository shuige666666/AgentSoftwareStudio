package com.core.multiAgentSoftwareStudio.Model.Benchmark;

/**
 * 记录某个 Agent 在本次基准运行中实际注入的模型 Bean。
 */
public record AgentModelAssignment(
        String agent,
        String modelRef
) {
}
