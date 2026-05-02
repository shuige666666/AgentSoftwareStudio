package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * 负责执行沙箱编译、运行和测试验证节点。
 */
@Service
public class VerificationNodeService {

    private final DockerSandboxService sandboxService;

    /**
     * 注入 Docker 沙箱服务。
     */
    public VerificationNodeService(DockerSandboxService sandboxService) {
        this.sandboxService = sandboxService;
    }

    /**
     * 在 Docker 沙箱中执行最终编译和运行验证
     */
    public SoftwareStudioWorkflowData run(SoftwareStudioWorkflowData data, Consumer<String> logger) {
        // 真正的编译/运行检查放在这里统一做。
        // 前面阶段只做轻量约束，避免每一层都进入昂贵的沙箱执行。
        logger.accept(
                "7. Running final compile/runtime verification in the sandbox. Attempt " + data.currentAttempt + ".");
        data.executionResult = sandboxService.runCodeInSandbox(
                Path.of(data.projectPath),
                data.structure.projectType(),
                data.structure.mainClassName());

        logger.accept("Execution result:");
        logger.accept("--------------------------------------------------");
        logger.accept(data.executionResult);
        logger.accept("--------------------------------------------------");
        return data;
    }

    /**
     * 在 Docker 沙箱中执行生成项目的测试。
     */
    public String runTests(SoftwareStudioWorkflowData data) {
        return sandboxService.runTestsInSandbox(Path.of(data.projectPath), data.structure.projectType());
    }
}
