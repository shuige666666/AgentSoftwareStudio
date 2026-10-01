package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.PlanningConflictDecision;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ApiEndpointContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectProfile;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectProfileService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workflow.DevelopmentMilestoneService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.VerificationResultService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.PlanningArtifactPersistenceService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.Node.PlanningNodeService;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceToolRegistryTest {
    /** 回放真实的短参数失败：修好 JSON 仍须检查补丁边界，只有显式重提完整补丁才落盘。 */
    @Test
    void replaysSavedMalformedArgumentsAndRequiresAnExplicitValidResubmission() throws Exception {
        String raw;
        try (var input = getClass().getResourceAsStream("/agent-runtime/note-ordered-incomplete-arguments.txt")) {
            raw = new String(java.util.Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
        }
        Path fixtureRoot = Path.of("src/test/resources/benchmark/note-form-v1");
        try (var files = Files.walk(fixtureRoot)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Path target = projectRoot.resolve(fixtureRoot.relativize(file));
                Files.createDirectories(target.getParent());
                Files.copy(file, target);
            }
        }
        var baseline = registry.snapshotWorkspace(projectRoot);
        var session = session(WorkspaceAgentMode.DEVELOPMENT, baseline);

        var jsonFailure = objectMapper.readTree(registry.execute("apply_patch", raw, session));
        assertEquals("TOOL_ARGUMENTS_INVALID_JSON", jsonFailure.path("error").asText());
        assertTrue(jsonFailure.path("message").asText().contains("outer JSON"));
        assertEquals(baseline, registry.snapshotWorkspace(projectRoot));
        assertEquals(0, session.writeVersion());

        // 离线补尾仅用于揭示第二层错误；平台没有自动修复或执行不完整调用。
        String patch = objectMapper.readTree(raw + "\"}").path("patch").asText();
        String completeArguments = objectMapper.writeValueAsString(Map.of("patch", patch));
        var patchFailure = objectMapper.readTree(registry.execute("apply_patch", completeArguments, session));
        assertFalse(patchFailure.path("ok").asBoolean());
        assertEquals("PATCH_FORMAT", patchFailure.path("error").asText());
        assertEquals(baseline, registry.snapshotWorkspace(projectRoot));
        assertEquals(0, session.writeVersion());

        String resubmittedPatch = patch.replace("*** End Patch\n*** Update File:", "*** Update File:");
        var result = objectMapper.readTree(registry.execute("apply_patch",
                objectMapper.writeValueAsString(Map.of("patch", resubmittedPatch)), session));
        assertTrue(result.path("ok").asBoolean(), result.toString());
        assertEquals(2, result.path("applied").size());
        assertTrue(Files.readString(projectRoot.resolve("src/main/java/com/example/notes/NoteController.java"))
                .contains("@PostMapping(\"/notes\")"));
        assertTrue(Files.readString(projectRoot.resolve("src/main/resources/templates/notes/form.html"))
                .contains("th:value"));
    }

    /** 不完整的小补丁也要报告 JSON 的真实失败位置，不能统一暗示补丁太长。 */
    @Test
    void reportsTruncatedJsonLocationWithoutExecutingThePatch() throws Exception {
        var session = session(WorkspaceAgentMode.DEVELOPMENT, Map.of());
        String feedback = registry.execute("apply_patch", "{\"patch\":\"*** Begin Patch\\n", session);
        var result = new ObjectMapper().readTree(feedback);
        assertEquals("TOOL_ARGUMENTS_INVALID_JSON", result.path("error").asText());
        assertTrue(result.path("message").asText().contains("line 1, column"));
        assertTrue(result.path("message").asText().contains("end-of-input"));
        assertTrue(result.path("message").asText().contains("No tool operation was executed"));
        assertEquals(0, session.writeVersion());
    }

    /** 环境故障不能覆盖之前尚未修好的业务或编译失败。 */
    @Test
    void keepsSourceFailureAcrossAnInfrastructureFailure() {
        var session = session(WorkspaceAgentMode.DEVELOPMENT, Map.of());
        session.recordVerificationFailure("AppTest expected 404 but was 200");
        WorkspaceToolRegistry.recordBashVerification(session, "mvn test",
                new SandboxExecutionResult("mvn test", null, "connection failed", 1, false, "Docker unavailable"));
        assertEquals("AppTest expected 404 but was 200", session.unresolvedVerificationFailure());
        WorkspaceToolRegistry.recordBashVerification(session, "mvn test",
                new SandboxExecutionResult("mvn test", 1, "unable to create native thread", 1, false, null));
        assertEquals("AppTest expected 404 but was 200", session.unresolvedVerificationFailure());
    }
    /** 规划读取提供完整契约与精确行范围，但不会改变规划内容或写入权限。 */
    @Test
    void readsWhitelistedPlanningAndKeepsMetadataProtected() throws Exception {
        Path contract = projectRoot.resolve(".software-studio/contract.json");
        Files.createDirectories(contract.getParent());
        String body = "{\r\n  \"errorResponses\": \"400/404\"\r\n}\r\n";
        Files.writeString(contract, body, StandardCharsets.UTF_8);
        var session = session(WorkspaceAgentMode.DEVELOPMENT, Map.of());
        assertTrue(registry.definitions(WorkspaceAgentMode.DEVELOPMENT).stream()
                .anyMatch(def -> def.name().equals("read_planning")));
        var full = objectMapper.readTree(registry.execute("read_planning",
                "{\"document\":\"contract.json\"}", session)).at("/files/0");
        var range = objectMapper.readTree(registry.execute("read_planning",
                "{\"document\":\"contract.json\",\"startLine\":2,\"endLine\":2}", session)).at("/files/0");

        assertEquals(body, full.path("content").asText());
        assertEquals("  \"errorResponses\": \"400/404\"\r\n", range.path("content").asText());
        assertEquals(full.path("sha256").asText(), range.path("sha256").asText());
        var invalid = objectMapper.readTree(registry.execute("read_planning",
                "{\"document\":\"../private.yml\"}", session));
        assertEquals("INVALID_PLANNING_DOCUMENT", invalid.path("error").asText());
        String write = registry.execute("apply_patch",
                "{\"patch\":\"*** Begin Patch\\n*** Delete File: .software-studio/contract.json\\n*** End Patch\"}", session);
        assertFalse(objectMapper.readTree(write).path("ok").asBoolean());
        assertEquals(body, Files.readString(contract));
        assertTrue(session.changedFiles().isEmpty());
    }


    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DockerSandboxService sandboxService = mock(DockerSandboxService.class);
    private final PlanningNodeService planningNodeService = mock(PlanningNodeService.class);
    private final WorkspaceCheckpointService checkpointService = mock(WorkspaceCheckpointService.class);
    private final WorkspaceToolRegistry registry = new WorkspaceToolRegistry(
            objectMapper,
            sandboxService,
            new WorkspaceService(new SourceCodePathService()),
            mock(ProjectProfileService.class),
            new WorkspacePatchService(),
            checkpointService,
            planningNodeService,
            new com.core.multiAgentSoftwareStudio.Service.Workflow.DevelopmentMilestoneService(objectMapper));

    @TempDir
    Path projectRoot;

    /** 大文件默认按行返回准确片段，续读范围、完整哈希及 CRLF 均保持一致。 */
    @Test
    void defaultSourceReadIsBoundedAndExplicitRangeCanReadRemainingLines() throws Exception {
        Path source = projectRoot.resolve("src/main/java/demo/Large.java");
        Files.createDirectories(source.getParent());
        String body = java.util.stream.IntStream.rangeClosed(1, 320)
                .mapToObj(index -> "line-" + index + "\r\n").collect(java.util.stream.Collectors.joining());
        Files.writeString(source, body, StandardCharsets.UTF_8);
        var session = session(WorkspaceAgentMode.DEVELOPMENT, Map.of());
        var first = objectMapper.readTree(registry.execute("read_files",
                "{\"paths\":[\"src/main/java/demo/Large.java\"]}", session)).at("/files/0");
        var rest = objectMapper.readTree(registry.execute("read_files",
                "{\"paths\":[\"src/main/java/demo/Large.java\"],\"startLine\":161,\"endLine\":320}", session)).at("/files/0");
        assertTrue(first.path("partial").asBoolean());
        assertEquals(160, first.path("endLine").asInt());
        assertEquals(320, first.path("totalLines").asInt());
        assertEquals(first.path("sha256").asText(), rest.path("sha256").asText());
        assertEquals(body, first.path("content").asText() + rest.path("content").asText());
        assertFalse(first.path("truncated").asBoolean());
        assertTrue(session.changedFiles().isEmpty());
    }

    /** Maven 原生库提示不能淹没实际 testCompile 错误。 */
    @Test
    void highlightsCompilerFailureAheadOfJansiAndHelpText() {
        String output = """
                Failed to load native library:jansi-2.4.0-libjansi.so
                java.lang.UnsatisfiedLinkError: /tmp/jansi-2.4.0-libjansi.so
                [ERROR] COMPILATION ERROR :
                [ERROR] /workspace/src/test/java/demo/Test.java:[9,47] package demo.missing does not exist
                  symbol:   class Missing
                  location: class demo.Test
                [ERROR] Failed to execute goal maven-compiler-plugin:testCompile
                [ERROR] -> [Help 1]
                [ERROR] To see the full stack trace, re-run Maven with -e.
                """;

        String highlights = WorkspaceToolRegistry.failureHighlights(output);

        assertTrue(highlights.contains("package demo.missing does not exist"));
        assertTrue(highlights.contains("symbol:   class Missing"));
        assertFalse(highlights.contains("jansi"));
        assertFalse(highlights.contains("[Help 1]"));
    }

    /** 整体测试失败时，前面测试类的绿色摘要不能占据错误摘要容量。 */
    @Test
    void skipsPassingTestClassSummariesBeforeActualFailures() {
        String output = "[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\n".repeat(12)
                + "[ERROR] Tests run: 16, Failures: 2, Errors: 1, Skipped: 0\n"
                + "[ERROR] ArticleControllerTest.shouldUpdate: expected 302 but was 200";

        String highlights = WorkspaceToolRegistry.failureHighlights(output);

        assertTrue(highlights.contains("Failures: 2, Errors: 1"));
        assertTrue(highlights.contains("expected 302 but was 200"));
        assertFalse(highlights.contains("Failures: 0, Errors: 0"));
    }

    /** 测试失败跨文件改动仍待验证；普通编译成功不能把它误清除。 */
    @Test
    void retainsFailedMavenTestUntilLaterGreenTestRun() {
        var data = new SoftwareStudioWorkflowData("test");
        var session = new WorkspaceAgentSession(projectRoot, WorkspaceAgentMode.DEVELOPMENT,
                data, message -> { }, Map.of(), Set.of());
        session.recordWrite("src/test/java/demo/AppTest.java");

        WorkspaceToolRegistry.recordBashVerification(session, "mvn test",
                new SandboxExecutionResult("mvn test", 1, """
                        Failed to load native library:jansi-2.4.0.so
                        [ERROR] /workspace/src/test/java/demo/AppTest.java:[57,35] method attribute cannot be applied
                        """, 1, false, null));
        session.recordWrite("src/main/java/demo/App.java");
        WorkspaceToolRegistry.recordBashVerification(session, "mvn compile",
                new SandboxExecutionResult("mvn compile", 0, "BUILD SUCCESS", 1, false, null));

        assertEquals(1, session.unresolvedFailureVersion());
        assertTrue(session.unresolvedVerificationFailure().contains("AppTest.java:[57,35]"));
        assertFalse(session.unresolvedVerificationFailure().contains("jansi"));

        WorkspaceToolRegistry.recordBashVerification(session, "mvn -Dtest=OtherTest test",
                new SandboxExecutionResult("mvn -Dtest=OtherTest test", 0,
                        "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0", 1, false, null));
        assertTrue(session.unresolvedVerificationFailure().contains("AppTest.java:[57,35]"));

        WorkspaceToolRegistry.recordBashVerification(session, "mvn test",
                new SandboxExecutionResult("mvn test", 0,
                        "Tests run: 4, Failures: 0, Errors: 0, Skipped: 0", 1, false, null));
        assertEquals("", session.unresolvedVerificationFailure());
    }

    /** run_tests 的定向成功不能冒充全套成功，也不能清除其他测试的错误。 */
    @Test
    void retainsRunTestsFailureUntilWholeSuitePasses() {
        var session = new WorkspaceAgentSession(projectRoot, WorkspaceAgentMode.DEVELOPMENT,
                new SoftwareStudioWorkflowData("test"), message -> { }, Map.of(), Set.of());
        session.recordWrite("src/test/java/demo/AppTest.java");
        WorkspaceToolRegistry.recordTestToolVerification(session, List.of(),
                new SandboxExecutionResult("mvn test", 1, "[ERROR] AppTest failed", 1, false, null));
        WorkspaceToolRegistry.recordTestToolVerification(session, List.of("OtherTest"),
                new SandboxExecutionResult("mvn -Dtest=OtherTest test", 0,
                        "Tests run: 1, Failures: 0, Errors: 0", 1, false, null));

        assertTrue(session.unresolvedVerificationFailure().contains("AppTest failed"));
        assertFalse(session.testsCurrent());

        WorkspaceToolRegistry.recordTestToolVerification(session, List.of(),
                new SandboxExecutionResult("mvn test", 0,
                        "Tests run: 2, Failures: 0, Errors: 0", 1, false, null));
        assertEquals("", session.unresolvedVerificationFailure());
        assertTrue(session.testsCurrent());
    }

    /** 截断的补丁参数应返回可执行的恢复建议，不进入磁盘写入。 */
    @Test
    void rejectsTruncatedPatchArgumentsWithRecoveryHint() {
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.projectPath = projectRoot.toString();
        WorkspaceAgentSession session = new WorkspaceAgentSession(projectRoot,
                WorkspaceAgentMode.DEVELOPMENT, data, message -> { }, Map.of(), Set.of());

        String result = registry.execute("apply_patch", "{\"patch\":\"*** Begin Patch", session);

        assertTrue(result.contains("TOOL_ARGUMENTS_INVALID_JSON"));
        assertTrue(result.contains("split large patches"));
        assertEquals(0, session.writeVersion());
    }

    /** 固定源码与模型补丁经真实工具入口落盘，复现统一补丁的空白上下文场景。 */
    @Test
    void appliesUnifiedDiffWithBlankContextThroughAgentTool() throws Exception {
        Path source = projectRoot.resolve("src/main/java/demo/Handler.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class Handler {\n\n    String mode = \"old\";\n}\n",
                StandardCharsets.UTF_8);
        String patch = """
                --- a/src/main/java/demo/Handler.java
                +++ b/src/main/java/demo/Handler.java
                @@ -1,4 +1,4 @@
                 class Handler {
                §
                -    String mode = "old";
                +    String mode = "new";
                 }
                """.replace('§', ' ');
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.projectPath = projectRoot.toString();
        WorkspaceAgentSession session = new WorkspaceAgentSession(projectRoot,
                WorkspaceAgentMode.DEVELOPMENT, data, message -> { }, Map.of(), Set.of());

        String result = registry.execute("apply_patch",
                objectMapper.writeValueAsString(Map.of("patch", patch)), session);

        assertTrue(objectMapper.readTree(result).path("ok").asBoolean(), result);
        assertTrue(Files.readString(source, StandardCharsets.UTF_8).contains("mode = \"new\""));
    }

    /** 显式强制模式按顺序接受 progress，全部接受后 final 才能结束。 */
    @Test
    void milestoneProgressControlsFinalCheckpoint() {
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("create then vote");
        data.milestonePolicy = com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestonePolicy.ORDERED;
        data.projectPath = projectRoot.toString();
        ProjectContract empty = new ProjectContract(java.util.List.of(), java.util.List.of(),
                java.util.List.of(), java.util.List.of());
        data.sliceDeliveryPlan = new SliceDeliveryPlan(java.util.List.of(
                new DeliverySlice("create", "create", java.util.List.of(), java.util.List.of(),
                        empty, java.util.List.of(), java.util.List.of()),
                new DeliverySlice("vote", "vote", java.util.List.of(), java.util.List.of(),
                        empty, java.util.List.of(), java.util.List.of())), java.util.List.of(), "test");
        var milestones = new com.core.multiAgentSoftwareStudio.Service.Workflow.DevelopmentMilestoneService(objectMapper);
        milestones.initialize(data);
        WorkspaceAgentSession session = new WorkspaceAgentSession(projectRoot,
                WorkspaceAgentMode.DEVELOPMENT, data, message -> { }, Map.of(), Set.of());
        var passing = new WorkspaceCheckpointService.CheckpointResult("progress", "PASSED", "hash",
                projectRoot.resolve("snapshot").toString(), projectRoot.resolve("report.json").toString(),
                null, null, true, java.util.List.of(), "", false, false);
        when(checkpointService.verify(any(), eq("progress"), anyString(), any())).thenReturn(passing);
        when(checkpointService.verify(any(), eq("final"), anyString())).thenReturn(
                new WorkspaceCheckpointService.CheckpointResult("final", "PASSED", "hash",
                        "snapshot", "report", null, null, true, java.util.List.of(), "", true, false));

        assertTrue(registry.execute("submit_checkpoint",
                "{\"scope\":\"final\",\"summary\":\"done\"}", session).contains("MILESTONES_PENDING"));
        assertTrue(registry.execute("submit_checkpoint",
                "{\"scope\":\"progress\",\"summary\":\"wrong\",\"milestoneId\":\"vote\"}", session)
                .contains("MILESTONE_OUT_OF_ORDER"));
        assertTrue(registry.execute("submit_checkpoint",
                "{\"scope\":\"progress\",\"summary\":\"create\",\"milestoneId\":\"create\"}", session)
                .contains("\"nextMilestone\":\"vote\""));
        assertTrue(registry.execute("submit_checkpoint",
                "{\"scope\":\"progress\",\"summary\":\"vote\",\"milestoneId\":\"vote\"}", session)
                .contains("\"allMilestonesAccepted\":true"));
        registry.execute("submit_checkpoint", "{\"scope\":\"final\",\"summary\":\"done\"}", session);
        assertTrue(session.completed());
    }

    /** 建议式可合并接受；没有记录也能申请 final，但失败的最终验证仍不能结束会话。 */
    @Test
    void advisoryAllowsMergedProgressAndFinalWithoutAcceptanceRecords() throws Exception {
        var data = new SoftwareStudioWorkflowData("three tasks");
        data.projectPath = projectRoot.toString();
        data.milestonePolicy = com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestonePolicy.ADVISORY;
        var empty = new ProjectContract(List.of(), List.of(), List.of(), List.of());
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                new DeliverySlice("a", "a", List.of(), List.of(), empty, List.of(), List.of()),
                new DeliverySlice("b", "b", List.of(), List.of(), empty, List.of(), List.of()),
                new DeliverySlice("c", "c", List.of(), List.of(), empty, List.of(), List.of())), List.of(), "test");
        new DevelopmentMilestoneService(objectMapper).initialize(data);
        var session = new WorkspaceAgentSession(projectRoot, WorkspaceAgentMode.DEVELOPMENT, data,
                message -> { }, Map.of(), Set.of());
        var passing = new WorkspaceCheckpointService.CheckpointResult("progress", "PASSED", "hash", "snapshot",
                "report", null, null, true, List.of(), "", false, false);
        when(checkpointService.verify(any(), eq("progress"), anyString(), any())).thenReturn(passing);
        when(checkpointService.verify(any(), eq("progress"), anyString())).thenReturn(passing);
        registry.execute("submit_checkpoint", "{\"scope\":\"progress\",\"summary\":\"diagnose\"}", session);
        assertTrue(data.acceptedSliceIds.isEmpty());
        String response = registry.execute("submit_checkpoint",
                "{\"scope\":\"progress\",\"summary\":\"merged\",\"milestoneIds\":[\"b\",\"c\"]}", session);
        assertTrue(objectMapper.readTree(response).path("acceptedMilestones").isArray(), response);
        assertEquals(List.of("b", "c"), data.acceptedSliceIds);
        when(checkpointService.verify(any(), eq("final"), anyString())).thenReturn(
                new WorkspaceCheckpointService.CheckpointResult("final", "FAILED", "hash", "snapshot", "report",
                        null, null, false, List.of("missing endpoint"), "missing endpoint", false, false));
        registry.execute("submit_checkpoint", "{\"scope\":\"final\",\"summary\":\"done\"}", session);
        assertFalse(session.completed());
        when(checkpointService.verify(any(), eq("final"), anyString())).thenReturn(
                new WorkspaceCheckpointService.CheckpointResult("final", "PASSED", "hash", "snapshot", "report",
                        null, null, true, List.of(), "", true, false));
        registry.execute("submit_checkpoint", "{\"scope\":\"final\",\"summary\":\"done\"}", session);
        assertTrue(session.completed());
        assertFalse(data.milestoneState.allAccepted());
    }

    /** 真实工具链依次保存快照、回滚候选、回归旧能力，并由 final 完成项目。 */
    @Test
    void acceptedCheckpointRestoresAndRegressesThroughFinal() throws Exception {
        Path source = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}\n", StandardCharsets.UTF_8);
        Files.writeString(projectRoot.resolve("pom.xml"), "<project/>\n", StandardCharsets.UTF_8);
        DockerSandboxService sandbox = mock(DockerSandboxService.class);
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp -DskipTests compile")))
                .thenReturn(new SandboxExecutionResult("compile", 0, "BUILD SUCCESS", 10, false, null));
        when(sandbox.runCheckpointMaven(any(), eq("mvn -o -B -ntp test")))
                .thenReturn(new SandboxExecutionResult("test", 0,
                        "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0", 10, false, null));
        ProjectProfileService profiles = mock(ProjectProfileService.class);
        List<Integer> checkedEndpoints = new ArrayList<>();
        when(profiles.evaluate(any(), any(), any(), eq(List.of()))).thenAnswer(invocation -> {
            ProjectContract checked = invocation.getArgument(2);
            checkedEndpoints.add(checked.endpoints().size());
            return ProjectQualityPolicyResult.empty();
        });
        var checkpoint = new WorkspaceCheckpointService(sandbox, new VerificationResultService(),
                profiles, new WorkspaceService(new SourceCodePathService()),
                new RunJournalService(), objectMapper);
        var milestones = new DevelopmentMilestoneService(objectMapper);
        var realRegistry = new WorkspaceToolRegistry(objectMapper, sandbox,
                new WorkspaceService(new SourceCodePathService()), profiles,
                new WorkspacePatchService(), checkpoint, planningNodeService, milestones);
        var data = new SoftwareStudioWorkflowData("create then vote");
        data.projectPath = projectRoot.toString();
        data.projectProfile = ProjectProfile.java17SpringBoot();
        var create = new ApiEndpointContract("POST", "/polls", "", "", "", "");
        var vote = new ApiEndpointContract("POST", "/polls/{id}/votes", "", "", "", "");
        data.contract = new ProjectContract(List.of(create, vote), List.of(), List.of(), List.of());
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                new DeliverySlice("create", "create", List.of(), List.of(),
                        new ProjectContract(List.of(create), List.of(), List.of(), List.of()), List.of(), List.of()),
                new DeliverySlice("vote", "vote", List.of("POST vote returns count"), List.of(),
                        new ProjectContract(List.of(vote), List.of(), List.of(), List.of()),
                        List.of("create"), List.of())), List.of(), "test");
        milestones.initialize(data);
        WorkspaceAgentSession session = new WorkspaceAgentSession(projectRoot,
                WorkspaceAgentMode.DEVELOPMENT, data, message -> { }, Map.of(), Set.of());

        String first = realRegistry.execute("submit_checkpoint",
                "{\"scope\":\"progress\",\"summary\":\"create\",\"milestoneId\":\"create\"}", session);
        assertTrue(first.contains("\"acceptedMilestone\":\"create\""));
        JsonNode handoff = objectMapper.readTree(first);
        assertEquals("POST vote returns count", handoff.path("nextAcceptanceCriteria").get(0).asText());
        assertEquals("/polls/{id}/votes", handoff.path("nextMilestoneContract")
                .path("endpoints").get(0).path("path").asText());
        assertFalse(session.completed());
        Files.writeString(source, "class App { int unfinished; }\n", StandardCharsets.UTF_8);
        session.recordWrite("src/main/java/demo/App.java");
        String restored = realRegistry.execute("restore_checkpoint", "{}", session);
        assertTrue(restored.contains("\"milestoneId\":\"create\""));
        assertEquals("class App {}\n", Files.readString(source, StandardCharsets.UTF_8));
        Files.writeString(source, "class App { int vote; }\n", StandardCharsets.UTF_8);
        session.recordWrite("src/main/java/demo/App.java");
        String second = realRegistry.execute("submit_checkpoint",
                "{\"scope\":\"progress\",\"summary\":\"vote\",\"milestoneId\":\"vote\"}", session);
        assertTrue(second.contains("\"allMilestonesAccepted\":true"));
        String finalResult = realRegistry.execute("submit_checkpoint",
                "{\"scope\":\"final\",\"summary\":\"done\"}", session);
        assertTrue(finalResult.contains("\"allowCompletion\":true"));
        assertTrue(session.completed());
        assertTrue(milestones.load(projectRoot).allAccepted());
        assertEquals(List.of(1, 2, 2), checkedEndpoints);
    }

    @Test
    void planningConflictReturnsDecisionWithoutBlockingDevelopmentSession() {
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("POST /api/polls");
        WorkspaceAgentSession session = new WorkspaceAgentSession(projectRoot,
                WorkspaceAgentMode.DEVELOPMENT, data, message -> { }, Map.of(), Set.of());
        session.messages().add(com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage.user("original goal"));
        when(planningNodeService.reviewConflict(any(), any(), any()))
                .thenReturn(new PlanningConflictDecision(false, "Keep the fixed path", null));

        String result = registry.execute("report_blocker",
                "{\"kind\":\"planning_conflict\",\"reason\":\"planned path differs\"}", session);

        assertTrue(result.contains("Keep the fixed path"));
        assertFalse(session.blocked());
        assertEquals("original goal", session.messages().getFirst().content());
    }

    @Test
    void planningMetadataIsExcludedFromAgentFileListingAndSnapshots() throws Exception {
        Path metadata = projectRoot.resolve(PlanningArtifactPersistenceService.METADATA_DIRECTORY);
        Files.createDirectories(metadata);
        Files.writeString(metadata.resolve("contract.json"), "{\"internal\":true}", StandardCharsets.UTF_8);
        Path source = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}", StandardCharsets.UTF_8);
        WorkspaceAgentSession session = session(WorkspaceAgentMode.IMPLEMENT, Map.of());

        String listed = registry.execute("list_files", "{}", session);
        Map<String, String> snapshot = registry.snapshotWorkspace(projectRoot);

        assertFalse(listed.contains(".software-studio"));
        assertFalse(snapshot.keySet().stream().anyMatch(path -> path.startsWith(".software-studio/")));
        assertTrue(snapshot.containsKey("src/main/java/demo/App.java"));
    }

    @Test
    void rejectsStaleEditAndPreservesCurrentFile() throws Exception {
        Path source = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}", StandardCharsets.UTF_8);
        WorkspaceAgentSession session = session(WorkspaceAgentMode.IMPLEMENT, Map.of(
                "src/main/java/demo/App.java", "class App {}"));

        String result = registry.execute("apply_edits", """
                {"edits":[{"path":"src/main/java/demo/App.java","expectedHash":"stale",
                "oldText":"class App {}","newText":"class App { int value; }"}]}
                """, session);

        assertTrue(result.contains("STALE_FILE"));
        assertEquals("class App {}", Files.readString(source, StandardCharsets.UTF_8));
        assertTrue(session.changedFiles().isEmpty());
    }

    @Test
    void testAgentCannotModifyProductionSource() throws Exception {
        Path source = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}", StandardCharsets.UTF_8);
        WorkspaceAgentSession session = session(WorkspaceAgentMode.TEST, Map.of());
        String hash = readHash(session, "src/main/java/demo/App.java");

        String result = registry.execute("apply_edits", """
                {"edits":[{"path":"src/main/java/demo/App.java","expectedHash":"%s",
                "oldText":"class App {}","newText":"class App { int value; }"}]}
                """.formatted(hash), session);

        assertTrue(result.contains("outside the current Agent scope"));
        assertEquals("class App {}", Files.readString(source, StandardCharsets.UTF_8));
    }

    @Test
    void repairTestAgentMayModifyProductionSourceWhenEvidenceCrossesOwnershipBoundary() throws Exception {
        Path source = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}", StandardCharsets.UTF_8);
        WorkspaceAgentSession session = session(WorkspaceAgentMode.REPAIR_TEST, Map.of(
                "src/main/java/demo/App.java", "class App {}"));
        String hash = readHash(session, "src/main/java/demo/App.java");

        String result = registry.execute("apply_edits", """
                {"edits":[{"path":"src/main/java/demo/App.java","expectedHash":"%s",
                "oldText":"class App {}","newText":"class App { int value; }"}]}
                """.formatted(hash), session);

        assertTrue(result.contains("changedFiles"));
        assertEquals("class App { int value; }", Files.readString(source, StandardCharsets.UTF_8));
    }

    @Test
    void completionRequiresVerificationAfterLatestWrite() throws Exception {
        WorkspaceAgentSession session = session(WorkspaceAgentMode.IMPLEMENT, Map.of());
        String missingHash = readHash(session, "src/main/java/demo/App.java");
        registry.execute("apply_edits", """
                {"edits":[{"path":"src/main/java/demo/App.java","expectedHash":"%s",
                "oldText":"","newText":"class App {}"}]}
                """.formatted(missingHash), session);

        String beforeCompile = registry.execute("complete_stage", "{\"summary\":\"done\"}", session);
        assertTrue(beforeCompile.contains("VERIFICATION_REQUIRED"));
        assertFalse(session.completed());

        when(sandboxService.runCompileInSandboxWithResult(any(), any()))
                .thenReturn(new SandboxExecutionResult("compile", 0, "BUILD SUCCESS", 1, false, null));
        registry.execute("compile_main", "{}", session);
        String completed = registry.execute("complete_stage", "{\"summary\":\"done\"}", session);

        assertTrue(completed.contains("accepted"));
        assertTrue(session.completed());
    }

    @Test
    void finalDiffIgnoresFilesRestoredToBaselineContent() throws Exception {
        Path source = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}", StandardCharsets.UTF_8);
        WorkspaceAgentSession session = session(WorkspaceAgentMode.IMPLEMENT, Map.of(
                "src/main/java/demo/App.java", "class App {}"));
        session.recordWrite("src/main/java/demo/App.java");

        assertTrue(registry.actualChangedFiles(session).isEmpty());
    }

    /** 片段读取保留 CRLF 与完整哈希，可以继续执行受哈希保护的精确编辑。 */
    @Test
    void readsLineRangeWithFullHashAndExactNewlines() throws Exception {
        String original = "first\r\nsecond\r\nthird\r\n";
        String path = "src/main/java/demo/Example.java";
        Files.createDirectories(projectRoot.resolve(path).getParent());
        Files.writeString(projectRoot.resolve(path), original, StandardCharsets.UTF_8);
        var session = new WorkspaceAgentSession(projectRoot, WorkspaceAgentMode.IMPLEMENT,
                new SoftwareStudioWorkflowData("test"), message -> { },
                Map.of(path, original), Set.of(path));
        String hash = readHash(session, path);
        JsonNode result = objectMapper.readTree(registry.execute("read_files",
                "{\"paths\":[\"src/main/java/demo/Example.java\"],\"startLine\":2,\"endLine\":2}", session)).at("/files/0");
        assertEquals("second\r\n", result.path("content").asText());
        assertEquals(hash, result.path("sha256").asText());
        assertEquals(3, result.path("totalLines").asInt());
        assertTrue(result.path("partial").asBoolean());
        var edit = objectMapper.createObjectNode();
        var item = edit.putArray("edits").addObject();
        item.put("path", path).put("expectedHash", hash);
        item.put("oldText", result.path("content").asText())
                .put("newText", "changed\r\n");
        JsonNode applied = objectMapper.readTree(registry.execute("apply_edits", edit.toString(), session));
        assertTrue(applied.has("changedFiles"), applied.toString());
        assertEquals("first\r\nchanged\r\nthird\r\n", Files.readString(projectRoot.resolve(path)));
    }

    /** 错误范围给出明确反馈，完整读取保持原语义。 */
    @Test
    void rejectsInvalidLineRangeAndPreservesFullRead() throws Exception {
        Files.writeString(projectRoot.resolve("README.md"), "first\nsecond", StandardCharsets.UTF_8);
        var session = session(WorkspaceAgentMode.DEVELOPMENT, Map.of());
        JsonNode invalid = objectMapper.readTree(registry.execute("read_files",
                "{\"paths\":[\"README.md\"],\"startLine\":2,\"endLine\":1}", session));
        assertEquals("INVALID_LINE_RANGE", invalid.path("error").asText());
        JsonNode full = objectMapper.readTree(registry.execute("read_files",
                "{\"paths\":[\"README.md\"]}", session)).at("/files/0");
        assertEquals("first\nsecond", full.path("content").asText());
        assertFalse(full.path("truncated").asBoolean());
    }

    private WorkspaceAgentSession session(WorkspaceAgentMode mode, Map<String, String> baseline) {
        return new WorkspaceAgentSession(
                projectRoot,
                mode,
                new SoftwareStudioWorkflowData("test"),
                message -> { },
                baseline,
                Set.of());
    }

    private String readHash(WorkspaceAgentSession session, String path) throws Exception {
        JsonNode result = objectMapper.readTree(registry.execute(
                "read_files", "{\"paths\":[\"" + path + "\"]}", session));
        return result.at("/files/0/sha256").asText();
    }
}
