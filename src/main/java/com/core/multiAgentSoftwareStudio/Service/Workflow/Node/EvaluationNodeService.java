package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Model.Generation.QualityPolicyFinding;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStepResult;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * 负责评估编译、运行、测试结果，并决定工作流是否进入修复循环。
 */
@Service
public class EvaluationNodeService {

    private final VerificationNodeService verificationNodeService;
    private final RunJournalService runJournalService;

    /**
     * 注入验证节点服务，用于在编译运行成功后继续执行测试。
     */
    public EvaluationNodeService(
            VerificationNodeService verificationNodeService,
            RunJournalService runJournalService) {
        this.verificationNodeService = verificationNodeService;
        this.runJournalService = runJournalService;
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
        data.pendingFailureKind = FailureKind.NONE;

        VerificationResult verification = data.verificationResult == null
                ? VerificationResult.empty()
                : data.verificationResult;
        if (verification.build().passed()) {
            logger.accept("8. Runtime/compile stage passed, running tests.");
            VerificationStepResult testStep = verificationNodeService.runTests(data);
            data.verificationResult = verification.withTest(testStep);
            runJournalService.recordVerification(data, testStep);
            logger.accept("Test result:");
            logger.accept(data.testResult);

            if (data.verificationResult.passed()) {
                var contractBlockers = data.qualityPolicyResult == null
                        ? java.util.List.<QualityPolicyFinding>of()
                        : data.qualityPolicyResult.contractBlockingFindings();
                if (!contractBlockers.isEmpty()) {
                    logger.accept("Technical verification passed; semantic contract gates still require repair.");
                    String contractEvidence = contractBlockers.stream()
                            .map(finding -> finding.gate() + ": " + finding.evidence())
                            .reduce((left, right) -> left + "\n" + right)
                            .orElse("Contract validation failed");
                    setPendingFailure(data, FailureKind.CONTRACT, contractEvidence);
                    data.shouldFix = !data.repairStopRequested && data.currentAttempt < data.maxRetries;
                    return data;
                }
                logger.accept("All generated tests passed.");
                data.success = true;
                return data;
            }
            setPendingFailure(data, data.verificationResult.primaryFailureKind(), data.testResult);
        } else {
            setPendingFailure(data, verification.primaryFailureKind(), data.executionResult);
        }

        // 修复被刻意延后到“全项目生成完成之后”。
        // 这样虽然最后一次修复看到的上下文更大，但总次数会少很多，
        // 整体 token 成本通常比“每个阶段都修”更低。
        data.shouldFix = !data.repairStopRequested && data.currentAttempt < data.maxRetries;
        if (!data.shouldFix) {
            logger.accept("Reached the repair limit. Returning the latest generated code.");
        }
        return data;
    }

    /**
     * 将结构化失败同步到旧的字符串字段，兼容现有 Debugger Prompt 和接口返回。
     */
    private void setPendingFailure(SoftwareStudioWorkflowData data, FailureKind failureKind, String log) {
        FailureKind safeKind = failureKind == null ? FailureKind.UNKNOWN : failureKind;
        data.pendingFailureKind = safeKind;
        data.pendingErrorType = safeKind.name();
        data.pendingFixLog = log == null ? "" : log;
    }
}
