package com.core.multiAgentSoftwareStudio.Service.Workflow.Node;

import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectProfile;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workflow.VerificationResultService;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VerificationNodeServiceTest {

    /**
     * Docker 必须使用归一化后的 Profile 类型，不能用架构师原始字符串误入原生 javac 分支。
     */
    @Test
    void usesEffectiveProfileProjectTypeForDocker() {
        DockerSandboxService sandbox = mock(DockerSandboxService.class);
        Path projectPath = Path.of("target", "verification-profile-test");
        when(sandbox.runCodeInSandboxWithResult(projectPath, "SPRING_BOOT", "com.example.BlogApplication"))
                .thenReturn(new SandboxExecutionResult(
                        "mvn -DskipTests package", 0, "BUILD SUCCESS", 10, false, null));
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.projectPath = projectPath.toString();
        data.structure = new ProjectStructure(
                "com.example", "WEB_APPLICATION", "com.example.BlogApplication", List.of());
        data.projectProfile = ProjectProfile.java17SpringBoot();

        new VerificationNodeService(
                sandbox, new VerificationResultService(), mock(RunJournalService.class))
                .run(data, message -> { });

        assertTrue(data.verificationResult.build().passed());
        verify(sandbox).runCodeInSandboxWithResult(projectPath, "SPRING_BOOT", "com.example.BlogApplication");
    }
}
