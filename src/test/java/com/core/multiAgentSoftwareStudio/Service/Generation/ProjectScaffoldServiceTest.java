package com.core.multiAgentSoftwareStudio.Service.Generation;

import com.core.multiAgentSoftwareStudio.Model.Generation.PrdDocument;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectProfile;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectProfileService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectScaffoldServiceTest {
    @TempDir Path project;

    /**
     * 业务编辑前应落下可编译的固定文件并执行真实编译入口。
     */
    @Test
    void createsVersionedSpringBootScaffoldBeforeBusinessFiles() throws Exception {
        DockerSandboxService sandbox = mock(DockerSandboxService.class);
        when(sandbox.runCompileInSandboxWithResult(any(), eq("SPRING_BOOT")))
                .thenReturn(new SandboxExecutionResult("mvn -DskipTests compile", 0, "BUILD SUCCESS", 100, false, null));
        ProjectProfileService profiles = mock(ProjectProfileService.class);
        when(profiles.resolve(any())).thenReturn(ProjectProfile.java17SpringBoot());
        ProjectScaffoldService service = new ProjectScaffoldService(profiles,
                new WorkspaceService(new SourceCodePathService()), sandbox);
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("投票");
        data.projectPath = project.toString();
        data.prd = new PrdDocument("投票", "创建和计票", List.of("B1: 创建投票"), List.of("Spring Boot"));
        data.structure = new ProjectStructure("com.example.polls", "SPRING_BOOT",
                "com.example.polls.PollsApplication", List.of());

        service.bootstrap(data, message -> { });

        assertTrue(Files.readString(project.resolve("pom.xml")).contains("<version>3.2.4</version>"));
        assertTrue(Files.readString(project.resolve("pom.xml"))
                .contains("<artifactId>spring-boot-starter-validation</artifactId>"));
        assertTrue(Files.readString(project.resolve("src/main/java/com/example/polls/PollsApplication.java"))
                .contains("@SpringBootApplication"));
        assertTrue(Files.exists(project.resolve("src/test/java/com/example/polls/PollsApplicationContextTest.java")));
        assertEquals(5, data.codes.size());
        assertTrue(data.scaffoldBuild.completedSuccessfully());
        verify(sandbox).runCompileInSandboxWithResult(any(), eq("SPRING_BOOT"));
        assertThrows(IllegalStateException.class, () -> service.bootstrap(data, message -> { }));
    }
}
