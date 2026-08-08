package com.core.multiAgentSoftwareStudio.Service.Preview;

import com.core.multiAgentSoftwareStudio.Model.Preview.PreviewStatus;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectPreviewServiceTest {

    @TempDir
    Path tempDir;

    private final DockerSandboxService dockerSandboxService = mock(DockerSandboxService.class);
    private final ProjectPreviewService previewService = new ProjectPreviewService(dockerSandboxService);

    @AfterEach
    void tearDown() {
        previewService.shutdown();
    }

    @Test
    void shouldStartSpringBootPreviewWithoutBlockingCaller() throws Exception {
        Path project = tempDir.resolve("demo-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<artifactId>spring-boot-maven-plugin</artifactId>");
        when(dockerSandboxService.startSpringBootPreview(any(Path.class), eq(8081))).thenReturn("container-1");
        when(dockerSandboxService.isContainerRunning("container-1")).thenReturn(true);
        when(dockerSandboxService.getContainerLogs("container-1"))
                .thenReturn("Started DemoApplication in 1.2 seconds (process running for 1.5)");

        String projectId = previewService.registerSuccessfulProject(project.toString());
        PreviewStatus starting = previewService.startPreview(projectId);

        assertThat(starting.state()).isEqualTo("STARTING");
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(previewService.getStatus(projectId).state()).isEqualTo("RUNNING"));
        assertThat(previewService.getStatus(projectId).url()).isEqualTo("http://localhost:8081");
    }

    @Test
    void shouldRejectNonSpringBootMavenProject() throws Exception {
        Path project = tempDir.resolve("plain-maven-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<artifactId>maven-compiler-plugin</artifactId>");

        assertThatThrownBy(() -> previewService.registerSuccessfulProject(project.toString()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不是 Spring Boot 项目");
    }

    @Test
    void shouldStopAndRemoveRunningPreview() throws Exception {
        Path project = tempDir.resolve("stoppable-project");
        Files.createDirectories(project);
        Files.writeString(project.resolve("pom.xml"), "<artifactId>spring-boot-starter-web</artifactId>");
        when(dockerSandboxService.startSpringBootPreview(any(Path.class), eq(8081))).thenReturn("container-2");
        when(dockerSandboxService.isContainerRunning("container-2")).thenReturn(true);
        when(dockerSandboxService.getContainerLogs("container-2"))
                .thenReturn("Started DemoApplication in 1.2 seconds (process running for 1.5)");

        String projectId = previewService.registerSuccessfulProject(project.toString());
        previewService.startPreview(projectId);
        await().atMost(Duration.ofSeconds(3))
                .untilAsserted(() -> assertThat(previewService.getStatus(projectId).state()).isEqualTo("RUNNING"));

        PreviewStatus stopped = previewService.stopPreview(projectId);

        assertThat(stopped.state()).isEqualTo("STOPPED");
        verify(dockerSandboxService).stopAndRemoveContainer("container-2");
    }
}
