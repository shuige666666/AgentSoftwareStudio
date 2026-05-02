package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 负责评估编译、运行、测试结果，并决定工作流是否进入修复循环。
 */
@Service
public class EvaluationNodeService {

    private final VerificationNodeService verificationNodeService;

    /**
     * 注入验证节点服务，用于在编译运行成功后继续执行测试。
     */
    public EvaluationNodeService(VerificationNodeService verificationNodeService) {
        this.verificationNodeService = verificationNodeService;
    }

    /**
     * 评估运行与测试结果，并决定是否进入修复流程
     */
    public SoftwareStudioWorkflowData execute(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        // 这个节点只负责“判断下一步该怎么走”：
        // - 成功：结束
        // - 失败且还能重试：进入 fix
        // - 失败且达到上限：返回当前结果
        data.shouldFix = false;
        data.pendingFixLog = null;
        data.pendingErrorType = null;

        String executionResult = data.executionResult == null ? "" : data.executionResult;
        boolean hasError = executionResult.contains("Exception")
                || executionResult.contains("Error")
                || executionResult.contains("failed")
                || executionResult.contains("error")
                || executionResult.contains("javac: file not found");

        if (!hasError) {
            logger.accept("8. Runtime/compile stage passed, running tests.");
            data.testResult = verificationNodeService.runTests(data);
            logger.accept("Test result:");
            logger.accept(data.testResult);

            if (data.testResult.contains("Failures: 0") && data.testResult.contains("Errors: 0")) {
                logger.accept("All generated tests passed.");
                data.success = true;
                return data;
            }
            if (data.testResult.contains("Failures:") || data.testResult.contains("Errors:")) {
                data.pendingErrorType = "LOGIC ERROR (TEST FAILURE)";
                data.pendingFixLog = data.testResult;
            } else {
                logger.accept("No concrete test summary found. Treating the build as successful.");
                data.success = true;
                return data;
            }
        } else {
            data.pendingErrorType = determineErrorType(executionResult);
            data.pendingFixLog = executionResult;
        }

        // 修复被刻意延后到“全项目生成完成之后”。
        // 这样虽然最后一次修复看到的上下文更大，但总次数会少很多，
        // 整体 token 成本通常比“每个阶段都修”更低。
        data.shouldFix = data.currentAttempt < data.maxRetries;
        if (!data.shouldFix) {
            logger.accept("Reached the repair limit. Returning the latest generated code.");
        }
        return data;
    }

    /**
     * 根据运行日志判断错误的大致类型
     */
    private String determineErrorType(String executionResult) {
        // 这里不是做非常精确的错误分类，而是为了决定后续 prompt 应该偏向哪种修复思路。
        if (executionResult.contains("javac:") || executionResult.contains("Compilation failure")) {
            return "COMPILATION ERROR";
        }
        if (executionResult.contains("Tests run:") && executionResult.contains("Failures:")) {
            if (!executionResult.contains("Failures: 0") || !executionResult.contains("Errors: 0")) {
                return "LOGIC ERROR (TEST FAILURE)";
            }
        }
        return "RUNTIME ERROR";
    }
}
