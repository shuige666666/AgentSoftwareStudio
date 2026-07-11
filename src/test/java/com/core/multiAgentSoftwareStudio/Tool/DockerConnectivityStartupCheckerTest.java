package com.core.multiAgentSoftwareStudio.Tool;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DockerConnectivityStartupCheckerTest {

    @Test
    void shouldPrintActionableWarningWhenDockerIsUnavailable() {
        DockerSandboxService sandboxService = mock(DockerSandboxService.class);
        when(sandboxService.isDockerAvailable()).thenReturn(false);
        DockerConnectivityStartupChecker checker = new DockerConnectivityStartupChecker(sandboxService);
        ByteArrayOutputStream errorOutput = new ByteArrayOutputStream();
        PrintStream originalError = System.err;

        try {
            System.setErr(new PrintStream(errorOutput, true, StandardCharsets.UTF_8));
            checker.checkConnectivity();
        } finally {
            System.setErr(originalError);
        }

        assertThat(errorOutput.toString(StandardCharsets.UTF_8))
                .contains("无法连接到本地 Docker")
                .contains("Docker Desktop 已经启动")
                .contains("容器验证与预览功能暂不可用");
    }

    @Test
    void shouldPrintSuccessMessageWhenDockerIsAvailable() {
        DockerSandboxService sandboxService = mock(DockerSandboxService.class);
        when(sandboxService.isDockerAvailable()).thenReturn(true);
        DockerConnectivityStartupChecker checker = new DockerConnectivityStartupChecker(sandboxService);
        ByteArrayOutputStream standardOutput = new ByteArrayOutputStream();
        PrintStream originalOutput = System.out;

        try {
            System.setOut(new PrintStream(standardOutput, true, StandardCharsets.UTF_8));
            checker.checkConnectivity();
        } finally {
            System.setOut(originalOutput);
        }

        assertThat(standardOutput.toString(StandardCharsets.UTF_8))
                .contains("Docker 连接正常")
                .contains("项目验证与预览功能可用");
    }
}
