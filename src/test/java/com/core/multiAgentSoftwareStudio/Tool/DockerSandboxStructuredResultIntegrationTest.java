package com.core.multiAgentSoftwareStudio.Tool;

import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStage;
import com.core.multiAgentSoftwareStudio.Service.Workflow.VerificationResultService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实 Docker Adapter 冒烟测试；默认不参与普通单元测试，只在显式开启时运行。
 */
@EnabledIfSystemProperty(named = "studio.docker.it", matches = "true")
class DockerSandboxStructuredResultIntegrationTest {

    @TempDir
    Path tempDir;

    /**
     * 同时验证成功和失败命令的真实容器退出码，防止再次退化为日志文本判断。
     */
    @Test
    void capturesRealDockerExitCodesForGreenAndBrokenProjects() throws Exception {
        DockerSandboxService sandboxService = new DockerSandboxService();
        assumeTrue(sandboxService.isDockerAvailable(), "本机 Docker 当前不可用");

        Path greenProject = tempDir.resolve("green-project");
        writeGreenMavenProject(greenProject);
        SandboxExecutionResult green = sandboxService.runTestsInSandboxWithResult(greenProject, "SPRING_BOOT");

        assertEquals(0, green.exitCode());
        assertTrue(green.output().contains("Tests run: 1"));
        assertTrue(new VerificationResultService().toStep(VerificationStage.TEST, green).passed());

        Path brokenProject = tempDir.resolve("broken-project");
        Files.createDirectories(brokenProject);
        Files.writeString(brokenProject.resolve("pom.xml"), "<project>", StandardCharsets.UTF_8);
        SandboxExecutionResult broken = sandboxService.runTestsInSandboxWithResult(brokenProject, "SPRING_BOOT");

        assertNotEquals(0, broken.exitCode());
        assertTrue(broken.output().contains("Non-readable POM")
                || broken.output().contains("POM")
                || broken.output().contains("project"));
        assertEquals(FailureKind.BUILD_PROFILE,
                new VerificationResultService().toStep(VerificationStage.TEST, broken).failureKind());
    }

    private void writeGreenMavenProject(Path project) throws Exception {
        Path testDirectory = project.resolve("src/test/java/com/example");
        Files.createDirectories(testDirectory);
        Files.writeString(project.resolve("pom.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>com.example</groupId>
                    <artifactId>docker-exit-code-smoke</artifactId>
                    <version>1.0.0</version>
                    <properties>
                        <maven.compiler.source>17</maven.compiler.source>
                        <maven.compiler.target>17</maven.compiler.target>
                    </properties>
                    <dependencies>
                        <dependency>
                            <groupId>org.junit.jupiter</groupId>
                            <artifactId>junit-jupiter</artifactId>
                            <version>5.9.2</version>
                            <scope>test</scope>
                        </dependency>
                    </dependencies>
                    <build>
                        <plugins>
                            <plugin>
                                <groupId>org.apache.maven.plugins</groupId>
                                <artifactId>maven-surefire-plugin</artifactId>
                                <version>3.2.5</version>
                            </plugin>
                        </plugins>
                    </build>
                </project>
                """, StandardCharsets.UTF_8);
        Files.writeString(testDirectory.resolve("SmokeTest.java"), """
                package com.example;
                import org.junit.jupiter.api.Test;
                import static org.junit.jupiter.api.Assertions.assertTrue;
                class SmokeTest {
                    @Test void passes() { assertTrue(true); }
                }
                """, StandardCharsets.UTF_8);
    }
}
