package com.core.multiAgentSoftwareStudio.Tool;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 显式启用真实仓库预取，验证固定 Maven 来源与只读候选挂载可协作。 */
@EnabledIfSystemProperty(named = "stage2.prefetch.smoke", matches = "true")
class DockerMavenPrefetchSmokeTest {
    @TempDir Path root;

    /** 预取容器只修改共享缓存，随后同一项目可在断网容器完成编译。 */
    @Test
    void prefetchesFromTrustedMavenMirror() throws Exception {
        Files.writeString(root.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion><groupId>demo</groupId>
                  <artifactId>prefetch-smoke</artifactId><version>1.0</version>
                  <properties><maven.compiler.release>17</maven.compiler.release></properties>
                  <dependencies><dependency><groupId>org.apache.commons</groupId>
                    <artifactId>commons-lang3</artifactId><version>3.12.0</version>
                  </dependency><dependency><groupId>org.junit.jupiter</groupId>
                    <artifactId>junit-jupiter</artifactId><version>5.10.2</version><scope>test</scope>
                  </dependency></dependencies>
                  <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId><version>3.11.0</version>
                  </plugin><plugin><groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-surefire-plugin</artifactId><version>3.2.5</version>
                  </plugin></plugins></build>
                </project>
                """, StandardCharsets.UTF_8);
        Path testSource = root.resolve("src/test/java/demo/DependencyTest.java");
        Files.createDirectories(testSource.getParent());
        Files.writeString(testSource, """
                package demo;
                class DependencyTest {
                    @org.junit.jupiter.api.Test void trims() {
                        org.junit.jupiter.api.Assertions.assertEquals("x", new Dependency().clean(" x "));
                    }
                }
                """, StandardCharsets.UTF_8);
        Path javaSource = root.resolve("src/main/java/demo/Dependency.java");
        Files.createDirectories(javaSource.getParent());
        Files.writeString(javaSource, """
                package demo;
                public class Dependency {
                    public String clean(String value) {
                        return org.apache.commons.lang3.StringUtils.trimToEmpty(value);
                    }
                }
                """, StandardCharsets.UTF_8);

        var sandbox = new DockerSandboxService();
        var result = sandbox.prefetchCheckpointDependencies(root);

        assertTrue(result.completedSuccessfully(), result.output() + " " + result.infrastructureError());
        assertTrue(Files.notExists(root.resolve("target")));
        var build = sandbox.runCheckpointMaven(root, "mvn -o -B -ntp -DskipTests compile");
        assertTrue(build.completedSuccessfully(), build.output() + " " + build.infrastructureError());
        var tests = sandbox.runCheckpointMaven(root, "mvn -o -B -ntp test");
        assertTrue(tests.completedSuccessfully(), tests.output() + " " + tests.infrastructureError());
        assertTrue(tests.output().contains("Tests run: 1"));
    }
}
