package com.core.multiAgentSoftwareStudio.Model.Workflow;

import com.core.multiAgentSoftwareStudio.Model.Generation.SourceCode;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
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
        LlmUsageSnapshot usage,
        ProjectQualityPolicyResult qualityPolicyResult,
        VerificationResult verification,
        WorkflowRunSummary runSummary) {

    public WorkflowExecutionResult {
        qualityPolicyResult = qualityPolicyResult == null
                ? ProjectQualityPolicyResult.empty()
                : qualityPolicyResult;
    }

    /**
     * 保留旧调用方式，便于已有单元测试和外围代码逐步迁移。
     */
    public WorkflowExecutionResult(
            List<SourceCode> codes,
            boolean platformSuccess,
            String projectPath,
            String executionResult,
            String testResult,
            List<String> validationWarnings,
            String pendingErrorType,
            int attemptsUsed,
            LlmUsageSnapshot usage) {
        this(codes, platformSuccess, projectPath, executionResult, testResult, validationWarnings,
                pendingErrorType, attemptsUsed, usage, ProjectQualityPolicyResult.empty(),
                VerificationResult.empty(), WorkflowRunSummary.empty());
    }

    /**
     * 保留阶段一至三期间的完整调用签名，缺少最终质量策略时按无阻断项处理。
     */
    public WorkflowExecutionResult(
            List<SourceCode> codes,
            boolean platformSuccess,
            String projectPath,
            String executionResult,
            String testResult,
            List<String> validationWarnings,
            String pendingErrorType,
            int attemptsUsed,
            LlmUsageSnapshot usage,
            VerificationResult verification,
            WorkflowRunSummary runSummary) {
        this(codes, platformSuccess, projectPath, executionResult, testResult, validationWarnings,
                pendingErrorType, attemptsUsed, usage, ProjectQualityPolicyResult.empty(), verification, runSummary);
    }
}
