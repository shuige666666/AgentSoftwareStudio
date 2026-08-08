package com.core.multiAgentSoftwareStudio.Model.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmUsageSnapshot;

import java.util.List;

/**
 * 一次真实工作流执行的完整结果。
 *
 * 普通生成接口仍然只返回源码；质量基准需要同时读取平台自判、验证输出和产物位置，
 * 因此通过这个结果对象保留可比较的运行证据。
 */
public record WorkflowExecutionResult(
        List<SourceCode> codes,
        boolean platformSuccess,
        String projectPath,
        String executionResult,
        String testResult,
        List<String> validationWarnings,
        String pendingErrorType,
        int attemptsUsed,
        LlmUsageSnapshot usage) {
}
