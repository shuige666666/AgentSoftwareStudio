package com.core.multiAgentSoftwareStudio.Tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 显式启用的真实 Docker 检查，验证非 root 挂载读写与平台元数据只读边界。
 */
@EnabledIfSystemProperty(named = "stage2.docker.smoke", matches = "true")
class DockerWorkspaceShellSmokeTest {
    @TempDir Path root;

    @Test
    void shellWritesOnlyCandidateWorkspace() throws Exception {
        Path metadata = root.resolve(".software-studio");
        Files.createDirectories(metadata);
        Files.writeString(metadata.resolve("plan.md"), "original", StandardCharsets.UTF_8);
        Path oldBuild = root.resolve("target/classes/Main.class");
        Files.createDirectories(oldBuild.getParent());
        Files.writeString(oldBuild, "old build", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion><groupId>demo</groupId>
                  <artifactId>shell-smoke</artifactId><version>1.0</version>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                  <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId><version>3.11.0</version>
                  </plugin></plugins></build>
                </project>
                """, StandardCharsets.UTF_8);
        Path javaSource = root.resolve("src/main/java/demo/Smoke.java");
        Files.createDirectories(javaSource.getParent());
        Files.writeString(javaSource, "package demo; public class Smoke {}", StandardCharsets.UTF_8);
        DockerSandboxService sandbox = new DockerSandboxService();
        DockerSandboxService.WorkspaceShell shell = sandbox.startWorkspaceShell(root);
        try {
            assertTrue(Files.notExists(oldBuild));
            var write = sandbox.runWorkspaceCommand(shell,
                    "id -u && mkdir -p src/main/java && printf 'created' > src/main/java/created.txt",
                    "", 30_000);
            assertTrue(write.execution().completedSuccessfully(), write.execution().output()
                    + " " + write.execution().infrastructureError());
            assertTrue(write.execution().output().contains("1000"));
            assertEquals("created", Files.readString(root.resolve("src/main/java/created.txt")));

            var absoluteCwd = sandbox.runWorkspaceCommand(shell, "pwd", "/workspace", 30_000);
            assertTrue(absoluteCwd.execution().completedSuccessfully());
            assertTrue(absoluteCwd.execution().output().contains("/workspace"));

            // 开发 Agent 常把构建输出接到 tail；前序失败必须仍报告给工具调用方。
            var pipedFailure = sandbox.runWorkspaceCommand(shell,
                    "false | tail -n 1", "/workspace", 30_000);
            assertEquals(1, pipedFailure.execution().exitCode());
            var pipedSuccess = sandbox.runWorkspaceCommand(shell,
                    "true | tail -n 1", "/workspace", 30_000);
            assertTrue(pipedSuccess.execution().completedSuccessfully());

            var build = sandbox.runWorkspaceCommand(shell, "mvn -q -DskipTests compile", "/workspace", 90_000);
            assertTrue(build.execution().completedSuccessfully(), build.execution().output()
                    + " " + build.execution().infrastructureError());
            assertTrue(Files.exists(root.resolve("target/classes/demo/Smoke.class")));

            var protectedWrite = sandbox.runWorkspaceCommand(shell,
                    "printf 'changed' > .software-studio/plan.md", "", 30_000);
            assertEquals(1, protectedWrite.execution().exitCode());
            assertEquals("original", Files.readString(metadata.resolve("plan.md")));
        } finally {
            sandbox.closeWorkspaceShell(shell);
        }
    }

    /** 正式 checkpoint 使用断网容器执行 Maven，并保留完整测试日志。 */
    @Test
    void checkpointMavenUsesIsolatedShell() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion><groupId>demo</groupId>
                  <artifactId>checkpoint-smoke</artifactId><version>1.0</version>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                  <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId><version>3.11.0</version>
                  </plugin></plugins></build>
                </project>
                """, StandardCharsets.UTF_8);
        Path javaSource = root.resolve("src/main/java/demo/Checkpoint.java");
        Files.createDirectories(javaSource.getParent());
        Files.writeString(javaSource, "package demo; public class Checkpoint {}", StandardCharsets.UTF_8);

        var result = new DockerSandboxService().runCheckpointMaven(root,
                "mvn -o -B -ntp -DskipTests compile");

        assertTrue(result.completedSuccessfully(), result.output() + " " + result.infrastructureError());
        assertTrue(result.output().contains("BUILD SUCCESS"));
        assertTrue(Files.exists(root.resolve("target/classes/demo/Checkpoint.class")));
    }
}
