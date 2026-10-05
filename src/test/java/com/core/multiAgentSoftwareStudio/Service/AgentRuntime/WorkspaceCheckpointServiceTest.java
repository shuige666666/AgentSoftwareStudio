package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectProfile;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectStructure;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestoneState;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectProfileService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workflow.VerificationResultService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class WorkspaceCheckpointServiceTest {
    @TempDir Path root;

    /** 既有用例提供交付说明，避免文档缺失掩盖各自需要验证的失败原因。 */
    @BeforeEach
    void writeDeliveryDocumentation() throws Exception {
        Files.writeString(root.resolve("README.md"), "# Demo\nRun: mvn spring-boot:run\nTest: mvn test\n", StandardCharsets.UTF_8);
    }

    /** README 中实际调用的 Wrapper 必须随工程交付；普通 Maven 命令和完整 Wrapper 均可通过。 */
    @Test
    void checksDocumentedWrapperCommandsAgainstSnapshotFiles() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        var sandbox = mock(DockerSandboxService.class);
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp -DskipTests compile")))
                .thenReturn(new SandboxExecutionResult("compile", 0, "BUILD SUCCESS", 1, false, null));
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp test")))
                .thenReturn(new SandboxExecutionResult("test", 0, "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0", 1, false, null));
        var profiles = mock(ProjectProfileService.class);
        when(profiles.evaluate(any(), any(), any(), any())).thenReturn(ProjectQualityPolicyResult.empty());
        var data = new SoftwareStudioWorkflowData("demo");
        data.projectProfile = ProjectProfile.java17SpringBoot();
        var session = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT,
                data, ignored -> {}, Map.of(), Set.of());
        var service = new WorkspaceCheckpointService(sandbox, new VerificationResultService(), profiles,
                new WorkspaceService(new SourceCodePathService()), new RunJournalService(), new ObjectMapper());
        assertTrue(service.verify(session, "final", "installed Maven").allowCompletion());
        Files.writeString(root.resolve("README.md"), "# Demo\n```bash\n./mvnw test\n```\n", StandardCharsets.UTF_8);
        assertTrue(service.verify(session, "progress", "still developing").deliveryFailure().isBlank());
        var missing = service.verify(session, "final", "wrapper commands");
        assertFalse(missing.allowCompletion());
        assertTrue(missing.deliveryFailure().contains("./mvnw"));
        assertTrue(missing.contractPassed());
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n");
        assertFalse(service.verify(session, "final", "incomplete wrapper").allowCompletion());
        Files.createDirectories(root.resolve(".mvn/wrapper"));
        Files.writeString(root.resolve(".mvn/wrapper/maven-wrapper.properties"), "distributionUrl=test\n");
        assertTrue(service.verify(session, "final", "complete wrapper files").allowCompletion());
        // Windows 命令也必须使用实际交付的脚本，不能借用 Unix 文件名。
        Files.writeString(root.resolve("README.md"), "```powershell\n.\\mvnw.cmd test\n```\n", StandardCharsets.UTF_8);
        var windowsMissing = service.verify(session, "final", "Windows wrapper");
        assertFalse(windowsMissing.allowCompletion());
        assertTrue(windowsMissing.deliveryFailure().contains("mvnw.cmd"));
        Files.writeString(root.resolve("mvnw.cmd"), "@echo off\n");
        assertTrue(service.verify(session, "final", "Windows files").allowCompletion());
        var mapper = new ObjectMapper();
        var legacyJson = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.valueToTree(missing);
        legacyJson.remove("deliveryFailure");
        var legacy = mapper.treeToValue(legacyJson, WorkspaceCheckpointService.CheckpointResult.class);
        assertTrue(legacy.deliveryFailure().isBlank());
    }

    /** 中途绿色可继续开发；缺失或空白说明不能交付，补齐后按新源码版本验收。 */
    @Test
    void finalRequiresDocumentationWithoutChangingFunctionalResultsOrBudget() throws Exception {
        Files.delete(root.resolve("README.md"));
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        var sandbox = mock(DockerSandboxService.class);
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp -DskipTests compile")))
                .thenReturn(new SandboxExecutionResult("compile", 0, "BUILD SUCCESS", 1, false, null));
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp test")))
                .thenReturn(new SandboxExecutionResult("test", 0, "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0", 1, false, null));
        var profiles = mock(ProjectProfileService.class);
        when(profiles.evaluate(any(), any(), any(), any())).thenReturn(ProjectQualityPolicyResult.empty());
        var data = new SoftwareStudioWorkflowData("demo");
        data.projectPath = root.toString();
        data.projectProfile = ProjectProfile.java17SpringBoot();
        data.developmentModelTurnsUsed = 15;
        data.developmentToolCallsUsed = 15;
        var session = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT, data, message -> { }, Map.of(), Set.of());
        var service = new WorkspaceCheckpointService(sandbox, new VerificationResultService(), profiles,
                new WorkspaceService(new SourceCodePathService()), new RunJournalService(), new ObjectMapper());

        var progress = service.verify(session, "progress", "green code");
        assertEquals("PASSED", progress.status());
        var missing = service.verify(session, "final", "ready");
        assertEquals("FAILED", missing.status());
        assertFalse(missing.allowCompletion());
        assertTrue(missing.build().passed());
        assertTrue(missing.test().passed());
        assertTrue(missing.contractPassed());
        assertTrue(missing.contractBlockers().isEmpty());
        assertTrue(missing.reusedVerification());
        assertTrue(WorkspaceCheckpointFeedback.failureSummary(missing).contains("README.md is missing or blank"));
        assertFalse(session.finalCheckpointPassed());
        assertFalse(data.success);

        Files.writeString(root.resolve("README.md"), " \n\t", StandardCharsets.UTF_8);
        var blank = service.verify(session, "final", "blank readme");
        assertFalse(blank.allowCompletion());
        assertNotEquals(missing.sourceHash(), blank.sourceHash());
        writeDeliveryDocumentation();
        var documented = service.verify(session, "final", "documented");
        assertTrue(documented.allowCompletion());
        assertNotEquals(blank.sourceHash(), documented.sourceHash());
        assertTrue(session.finalCheckpointPassed());
        assertEquals(15, data.developmentModelTurnsUsed);
        assertEquals(15, data.developmentToolCallsUsed);
    }

    /** 自测绿色不能掩盖 HTTP 失败；progress 提供反馈，final 必须通过独立检查。 */
    @Test
    void publicBehaviorFailureBlocksFinalAndRechecksAfterRepair() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        var sandbox = mock(DockerSandboxService.class);
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp -DskipTests compile")))
                .thenReturn(new SandboxExecutionResult("compile", 0, "BUILD SUCCESS", 1, false, null));
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp test")))
                .thenReturn(new SandboxExecutionResult("test", 0, "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0", 1, false, null));
        var profiles = mock(ProjectProfileService.class);
        when(profiles.evaluate(any(), any(), any(), any())).thenReturn(ProjectQualityPolicyResult.empty());
        var behavior = mock(com.core.multiAgentSoftwareStudio.Service.Benchmark.BenchmarkBehaviorEvaluator.class);
        var failed = com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult.fromFindings(List.of(
                new com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorFinding("link.create", false,
                        "HTTP 200", "FAIL", "POST /api/links {url:https://example.org/docs}", "201; code 非空")));
        var passed = com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult.fromFindings(List.of(
                new com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorFinding("link.create", true, "HTTP 201")));
        when(behavior.evaluate(eq("url-shortener-behavior-v1"), any())).thenAnswer(invocation -> {
            Path work = Path.of(invocation.getArgument(1, String.class));
            assertTrue(work.endsWith("behavior-work"));
            assertFalse(work.equals(root));
            // 检查过程也不能改写候选或冻结的 source。
            Files.writeString(work.resolve("pom.xml"), "verification-only mutation");
            return failed;
        });
        var data = new SoftwareStudioWorkflowData("public contract");
        data.projectPath = root.toString();
        data.projectProfile = ProjectProfile.java17SpringBoot();
        data.behaviorCheckId = "url-shortener-behavior-v1";
        data.contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        var session = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT, data, ignored -> {}, Map.of(), Set.of());
        var service = new WorkspaceCheckpointService(sandbox, new VerificationResultService(), profiles,
                new WorkspaceService(new SourceCodePathService()), new RunJournalService(), new ObjectMapper(), behavior);
        var progress = service.verify(session, "progress", "mid-development");
        assertEquals("PASSED", progress.status());
        assertEquals("FAIL", progress.behavior().status());
        assertFalse(progress.allowCompletion());
        assertTrue(progress.failureOutput().contains("sourceHash=" + progress.sourceHash()));
        var finalFailed = service.verify(session, "final", "complete");
        assertEquals("FAILED", finalFailed.status());
        assertTrue(finalFailed.reusedVerification());
        assertFalse(finalFailed.allowCompletion());
        assertEquals("<project/>", Files.readString(root.resolve("pom.xml")));
        assertEquals("<project/>", Files.readString(Path.of(finalFailed.snapshotPath()).resolve("pom.xml")));
        when(behavior.evaluate(eq("url-shortener-behavior-v1"), any())).thenReturn(passed);
        Files.writeString(root.resolve("pom.xml"), "<project><!-- repaired --></project>");
        session.recordWrite("pom.xml");
        var fixed = service.verify(session, "final", "repaired");
        assertTrue(fixed.allowCompletion());
        assertNotEquals(finalFailed.sourceHash(), fixed.sourceHash());
        when(behavior.evaluate(eq("url-shortener-behavior-v1"), any())).thenReturn(
                com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult.environmentError("Docker unavailable"));
        var unavailable = service.verify(session, "final", "recheck");
        assertEquals("BLOCKED", unavailable.status());
        assertFalse(unavailable.allowCompletion());
        verify(behavior, times(4)).evaluate(eq("url-shortener-behavior-v1"), any());
    }

    /** 生成测试红色时独立业务仍可判卷，但业务通过不能放行失败测试。 */
    @Test
    void evaluatesBehaviorDespiteFailedGeneratedTests() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        var sandbox = mock(DockerSandboxService.class);
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp -DskipTests compile")))
                .thenReturn(new SandboxExecutionResult("compile", 0, "BUILD SUCCESS", 1, false, null));
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp test")))
                .thenReturn(new SandboxExecutionResult("test", 1, "Assertion failure", 1, false, null));
        var behavior = mock(com.core.multiAgentSoftwareStudio.Service.Benchmark.BenchmarkBehaviorEvaluator.class);
        when(behavior.evaluate(any(), any())).thenReturn(
                com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult.fromFindings(List.of(
                        new com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorFinding("poll.create", true, "HTTP 201"))));
        var data = new SoftwareStudioWorkflowData("public contract");
        data.projectPath = root.toString();
        data.projectProfile = ProjectProfile.java17SpringBoot();
        data.behaviorCheckId = "poll-system-behavior-v1";
        var session = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT, data, ignored -> {}, Map.of(), Set.of());
        var service = new WorkspaceCheckpointService(sandbox, new VerificationResultService(), mock(ProjectProfileService.class),
                new WorkspaceService(new SourceCodePathService()), new RunJournalService(), new ObjectMapper(), behavior);
        var result = service.verify(session, "final", "ready");
        assertEquals("PASS", result.behavior().status());
        assertEquals("FAILED", result.status());
        assertFalse(result.allowCompletion());
        assertTrue(result.failureOutput().contains("Assertion failure"));
        when(behavior.evaluate(any(), any())).thenReturn(
                com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult.environmentError("Docker unavailable"));
        var testAndEnvironmentFailure = service.verify(session, "final", "retry");
        assertEquals("FAILED", testAndEnvironmentFailure.status());
        assertFalse(testAndEnvironmentFailure.allowCompletion());
        assertTrue(testAndEnvironmentFailure.failureOutput().contains("Assertion failure"));
    }

    /** 正式冻结验证将新报告的失败方法和源码位置交还会话，不改写原日志或放行失败。 */
    @Test
    void includesFreshSurefireEvidenceInFailedCheckpoint() throws Exception {
        Path testSource = root.resolve("src/test/java/demo/AppTest.java");
        Files.createDirectories(testSource.getParent());
        Files.writeString(testSource, "package demo; class AppTest {}");
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        DockerSandboxService sandbox = mock(DockerSandboxService.class);
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp -DskipTests compile")))
                .thenReturn(new SandboxExecutionResult("compile", 0, "BUILD SUCCESS", 1, false, null));
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp test"))).thenAnswer(invocation -> {
            Path work = invocation.getArgument(0);
            Path report = work.resolve("target/surefire-reports/TEST-demo.AppTest.xml");
            Files.createDirectories(report.getParent());
            Files.writeString(report, """
                    <testsuite><testcase classname="demo.AppTest" name="shouldReturn404">
                    <failure message="Status expected:&lt;404&gt; but was:&lt;200&gt;">at demo.AppTest.setUp(AppTest.java:22)</failure>
                    </testcase></testsuite>
                    """);
            return new SandboxExecutionResult("mvn test", 1, "RAW TEST LOG: failed", 1, false, null);
        });
        var data = new SoftwareStudioWorkflowData("demo");
        data.projectPath = root.toString();
        data.projectProfile = ProjectProfile.java17SpringBoot();
        var session = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT,
                data, message -> { }, Map.of(), Set.of());
        var service = new WorkspaceCheckpointService(sandbox, new VerificationResultService(),
                mock(ProjectProfileService.class), new WorkspaceService(new SourceCodePathService()),
                new RunJournalService(), new ObjectMapper());

        var result = service.verify(session, "final", "ready");

        assertEquals("FAILED", result.status());
        assertFalse(result.allowCompletion());
        assertTrue(result.failureOutput().contains("demo.AppTest.shouldReturn404"));
        assertTrue(result.failureOutput().contains("expected:<404> but was:<200>"));
        assertTrue(result.failureOutput().contains("src/test/java/demo/AppTest.java:22"));
        assertEquals("RAW TEST LOG: failed", Files.readString(Path.of(result.reportPath()).getParent().resolve("test.log")));
    }

    /**
     * progress 绿色仍继续开发；final 在新快照完成全量验证后才允许结束。
     */
    @Test
    void verifiesIndependentSnapshotAndSeparatesProgressFromFinal() throws Exception {
        Path source = root.resolve("src/main/java/demo/Demo.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Demo {}\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("target"));
        Files.writeString(root.resolve("target/old.class"), "noise");
        DockerSandboxService sandbox = mock(DockerSandboxService.class);
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp -DskipTests compile")))
                .thenAnswer(invocation -> {
                    Path work = invocation.getArgument(0);
                    Files.writeString(work.resolve("src/main/java/demo/Demo.java"), "class Demo { int changed; }\n");
                    return new SandboxExecutionResult("mvn -DskipTests compile", 0,
                            "BUILD SUCCESS", 10, false, null);
                });
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp test")))
                .thenReturn(new SandboxExecutionResult("mvn test", 0,
                        "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0", 20, false, null));
        ProjectProfileService profiles = mock(ProjectProfileService.class);
        when(profiles.evaluate(any(), any(), any(), eq(List.of()))).thenReturn(ProjectQualityPolicyResult.empty());
        WorkspaceCheckpointService service = new WorkspaceCheckpointService(sandbox,
                new VerificationResultService(), profiles,
                new WorkspaceService(new SourceCodePathService()), new RunJournalService(), new ObjectMapper());
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("demo");
        data.projectPath = root.toString();
        data.projectProfile = ProjectProfile.java17SpringBoot();
        data.structure = new ProjectStructure("demo", "SPRING_BOOT", "demo.Demo", List.of());
        data.contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        WorkspaceAgentSession session = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT,
                data, message -> { }, Map.of(), Set.of());

        var progress = service.verify(session, "progress", "first implementation");
        assertEquals("PASSED", progress.status());
        assertEquals("NOT_APPLICABLE", progress.behavior().status());
        assertFalse(progress.allowCompletion());
        assertFalse(data.success);
        assertFalse(Files.exists(Path.of(progress.snapshotPath()).resolve("target/old.class")));
        assertEquals("class Demo {}\n", Files.readString(Path.of(progress.snapshotPath())
                .resolve("src/main/java/demo/Demo.java")));

        Files.writeString(source, "class Demo { int value; }\n", StandardCharsets.UTF_8);
        session.recordWrite("src/main/java/demo/Demo.java");
        var finished = service.verify(session, "final", "complete project");
        assertTrue(finished.allowCompletion());
        assertTrue(data.success);
        assertNotEquals(progress.sourceHash(), finished.sourceHash());
        assertTrue(Files.exists(Path.of(finished.reportPath())));
        verify(profiles).evaluate(any(), any(), any(), eq(List.of()));
    }

    /** 测试失败不能被编译通过或模型的 final 声明覆盖。 */
    @Test
    void reusesPassingProgressBuildAndTestsForUnchangedFinalSource() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        DockerSandboxService sandbox = mock(DockerSandboxService.class);
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp -DskipTests compile")))
                .thenReturn(new SandboxExecutionResult("mvn compile", 0, "BUILD SUCCESS", 10, false, null));
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp test")))
                .thenReturn(new SandboxExecutionResult("mvn test", 0,
                        "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0", 20, false, null));
        ProjectProfileService profiles = mock(ProjectProfileService.class);
        when(profiles.evaluate(any(), any(), any(), eq(List.of())))
                .thenReturn(ProjectQualityPolicyResult.empty());
        var data = new SoftwareStudioWorkflowData("demo");
        data.projectPath = root.toString();
        data.projectProfile = ProjectProfile.java17SpringBoot();
        data.contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        var session = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT,
                data, message -> { }, Map.of(), Set.of());
        var service = new WorkspaceCheckpointService(sandbox, new VerificationResultService(),
                profiles, new WorkspaceService(new SourceCodePathService()),
                new RunJournalService(), new ObjectMapper());

        var progress = service.verify(session, "progress", "green");
        var finished = service.verify(session, "final", "ready");

        assertTrue(finished.allowCompletion());
        assertTrue(finished.reusedVerification());
        assertEquals(progress.sourceHash(), finished.sourceHash());
        verify(sandbox, times(1)).runCheckpointMaven(any(), eq("mvn -o -B -ntp -DskipTests compile"));
        verify(sandbox, times(1)).runCheckpointMaven(any(), eq("mvn -o -B -ntp test"));
    }

    @Test
    void finalFailureReturnsToDevelopment() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        DockerSandboxService sandbox = mock(DockerSandboxService.class);
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp -DskipTests compile")))
                .thenReturn(new SandboxExecutionResult("mvn compile", 0, "BUILD SUCCESS", 10, false, null));
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp test")))
                .thenReturn(new SandboxExecutionResult("mvn test", 1,
                        "Tests run: 1, Failures: 1, Errors: 0, Skipped: 0", 20, false, null));
        var data = new SoftwareStudioWorkflowData("demo");
        data.projectPath = root.toString();
        data.projectProfile = ProjectProfile.java17SpringBoot();
        var session = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT,
                data, message -> { }, Map.of(), Set.of());
        var service = new WorkspaceCheckpointService(sandbox, new VerificationResultService(),
                mock(ProjectProfileService.class), new WorkspaceService(new SourceCodePathService()),
                new RunJournalService(), new ObjectMapper());

        var result = service.verify(session, "final", "ready");

        assertEquals("FAILED", result.status());
        assertFalse(result.allowCompletion());
        assertFalse(session.finalCheckpointPassed());
        assertFalse(data.success);
    }

    /** 离线缓存缺项先预取可信仓库，成功后在断网容器重试正式构建。 */
    @Test
    void prefetchesMissingMavenDependenciesAndRetriesOffline() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        DockerSandboxService sandbox = mock(DockerSandboxService.class);
        String buildCommand = "mvn -o -B -ntp -DskipTests compile";
        when(sandbox.runCheckpointMaven(any(), eq(buildCommand)))
                .thenReturn(new SandboxExecutionResult(buildCommand, 1,
                                "Cannot access central in offline mode; artifact has not been downloaded from it before",
                                10, false, null),
                        new SandboxExecutionResult(buildCommand, 0, "BUILD SUCCESS", 20, false, null));
        when(sandbox.prefetchCheckpointDependencies(any()))
                .thenReturn(new SandboxExecutionResult("mvn dependency:go-offline", 0,
                        "BUILD SUCCESS", 30, false, null));
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp test")))
                .thenReturn(new SandboxExecutionResult("mvn test", 0,
                        "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0", 20, false, null));
        ProjectProfileService profiles = mock(ProjectProfileService.class);
        when(profiles.evaluate(any(), any(), any(), eq(List.of()))).thenReturn(ProjectQualityPolicyResult.empty());
        var data = new SoftwareStudioWorkflowData("demo");
        data.projectPath = root.toString();
        data.projectProfile = ProjectProfile.java17SpringBoot();
        data.contract = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        var session = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT,
                data, message -> { }, Map.of(), Set.of());
        var service = new WorkspaceCheckpointService(sandbox, new VerificationResultService(),
                profiles, new WorkspaceService(new SourceCodePathService()),
                new RunJournalService(), new ObjectMapper());

        var result = service.verify(session, "final", "ready");

        assertTrue(result.allowCompletion());
        verify(sandbox, times(2)).runCheckpointMaven(any(), eq(buildCommand));
        verify(sandbox).prefetchCheckpointDependencies(any());
        assertTrue(Files.exists(Path.of(result.reportPath()).getParent().resolve("dependency-prefetch.log")));
    }

    /** 生产源码编译失败不应伪装为缺依赖，也不能启动联网预取。 */
    @Test
    void compileErrorDoesNotStartDependencyPrefetch() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        DockerSandboxService sandbox = mock(DockerSandboxService.class);
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp -DskipTests compile")))
                .thenReturn(new SandboxExecutionResult("mvn compile", 1,
                        "[ERROR] cannot find symbol", 10, false, null));
        var data = new SoftwareStudioWorkflowData("demo");
        data.projectPath = root.toString();
        data.projectProfile = ProjectProfile.java17SpringBoot();
        var session = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT,
                data, message -> { }, Map.of(), Set.of());
        var service = new WorkspaceCheckpointService(sandbox, new VerificationResultService(),
                mock(ProjectProfileService.class), new WorkspaceService(new SourceCodePathService()),
                new RunJournalService(), new ObjectMapper());

        var result = service.verify(session, "final", "ready");

        assertEquals("FAILED", result.status());
        verify(sandbox, never()).prefetchCheckpointDependencies(any());
    }

    /** 可信仓库不可达时标记环境阻断，避免让模型误改正常源码。 */
    @Test
    void unavailableDependencyRepositoryBlocksCheckpoint() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>");
        DockerSandboxService sandbox = mock(DockerSandboxService.class);
        String command = "mvn -o -B -ntp -DskipTests compile";
        when(sandbox.runCheckpointMaven(any(), eq(command)))
                .thenReturn(new SandboxExecutionResult(command, 1,
                        "Artifact has not been downloaded from it before in offline mode", 10, false, null));
        when(sandbox.prefetchCheckpointDependencies(any()))
                .thenReturn(new SandboxExecutionResult("mvn dependency:go-offline", null,
                        "Could not transfer artifact", 20, false, "Trusted Maven repository unavailable"));
        var data = new SoftwareStudioWorkflowData("demo");
        data.projectPath = root.toString();
        data.projectProfile = ProjectProfile.java17SpringBoot();
        var session = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT,
                data, message -> { }, Map.of(), Set.of());
        var service = new WorkspaceCheckpointService(sandbox, new VerificationResultService(),
                mock(ProjectProfileService.class), new WorkspaceService(new SourceCodePathService()),
                new RunJournalService(), new ObjectMapper());

        var result = service.verify(session, "final", "ready");

        assertEquals("BLOCKED", result.status());
        assertFalse(result.allowCompletion());
    }

    /** 回滚只恢复已验收源码，当前候选先备份，平台规划文件保持原样。 */
    @Test
    void restoresLatestAcceptedSourceWithoutTouchingMetadata() throws Exception {
        Path source = root.resolve("src/main/java/demo/Demo.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Demo {}\n");
        Path metadata = root.resolve(".software-studio/plan.md");
        Files.createDirectories(metadata.getParent());
        Files.writeString(metadata, "original plan");
        DockerSandboxService sandbox = mock(DockerSandboxService.class);
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp -DskipTests compile")))
                .thenReturn(new SandboxExecutionResult("mvn compile", 0, "BUILD SUCCESS", 10, false, null));
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp test")))
                .thenReturn(new SandboxExecutionResult("mvn test", 0,
                        "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0", 20, false, null));
        var data = new SoftwareStudioWorkflowData("demo");
        data.projectPath = root.toString();
        data.projectProfile = ProjectProfile.java17SpringBoot();
        var session = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT,
                data, message -> { }, Map.of(), Set.of());
        var service = new WorkspaceCheckpointService(sandbox, new VerificationResultService(),
                mock(ProjectProfileService.class), new WorkspaceService(new SourceCodePathService()),
                new RunJournalService(), new ObjectMapper());
        var accepted = service.verify(session, "progress", "first capability");
        data.milestoneState = new DevelopmentMilestoneState(1, List.of(
                new DevelopmentMilestoneState.Task("first", "first", List.of(), true,
                        accepted.sourceHash(), accepted.snapshotPath(), accepted.reportPath())), 0, 0, "");
        Files.writeString(source, "class Demo { int unfinished; }\n");
        Path extra = root.resolve("src/main/java/demo/Extra.java");
        Files.writeString(extra, "class Extra {}\n");

        var restored = service.restoreLatestAccepted(session);

        assertEquals("class Demo {}\n", Files.readString(source));
        assertFalse(Files.exists(extra));
        assertEquals("original plan", Files.readString(metadata));
        assertEquals("class Demo { int unfinished; }\n", Files.readString(
                Path.of(restored.backupPath()).resolve("src/main/java/demo/Demo.java")));
        assertEquals(accepted.sourceHash(), restored.restoredHash());
    }
}
