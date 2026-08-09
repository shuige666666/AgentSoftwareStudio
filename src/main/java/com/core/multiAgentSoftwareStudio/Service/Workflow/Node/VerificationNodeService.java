package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workflow.VerificationResultService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStage;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStepResult;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 负责执行沙箱编译、运行和测试验证节点。
 */
@Service
public class VerificationNodeService {

    private final DockerSandboxService sandboxService;
    private final VerificationResultService verificationResultService;
    private final RunJournalService runJournalService;

    /**
     * 注入 Docker 沙箱服务。
     */
    public VerificationNodeService(
            DockerSandboxService sandboxService,
            VerificationResultService verificationResultService,
            RunJournalService runJournalService) {
        this.sandboxService = sandboxService;
        this.verificationResultService = verificationResultService;
        this.runJournalService = runJournalService;
    }

    /**
     * 在 Docker 沙箱中执行最终编译和运行验证
     */
    public SoftwareStudioWorkflowData run(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        // 真正的编译/运行检查放在这里统一做。
        // 前面阶段只做轻量约束，避免每一层都进入昂贵的沙箱执行。
        logger.accept(
                "7. Running final compile/runtime verification in the sandbox. Attempt " + data.currentAttempt + ".");
        SandboxExecutionResult execution = sandboxService.runCodeInSandboxWithResult(
                Path.of(data.projectPath),
                data.structure.projectType(),
                data.structure.mainClassName());
        data.executionResult = execution.output();
        VerificationStepResult build = verificationResultService.toStep(VerificationStage.BUILD, execution);
        data.verificationResult = VerificationResult.afterBuild(build);
        runJournalService.recordVerification(data, build);

        logger.accept("Execution result:");
        logger.accept("--------------------------------------------------");
        logger.accept(data.executionResult);
        logger.accept("--------------------------------------------------");
        return data;
    }

    /**
     * 在 Docker 沙箱中执行生成项目的测试。
     */
    public VerificationStepResult runTests(SoftwareStudioWorkflowData data) {
        SandboxExecutionResult execution = sandboxService.runTestsInSandboxWithResult(
                Path.of(data.projectPath), data.structure.projectType());
        data.testResult = execution.output();
        return verificationResultService.toStep(VerificationStage.TEST, execution);
    }
}
