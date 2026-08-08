package com.core.multiAgentSoftwareStudio.Model.Benchmark;

/**
 * 记录基准运行实际请求的模型配置。供应商未暴露内部版本时，revision 明确记为 unknown。
 */
public record BenchmarkModelInfo(
        String requestedName,
        String reportedName,
        String provider,
        String baseUrl,
        double temperature,
        int maxOutputTokens,
        long timeoutSeconds,
        String revision,
        String manualReleaseLabel) {
}
