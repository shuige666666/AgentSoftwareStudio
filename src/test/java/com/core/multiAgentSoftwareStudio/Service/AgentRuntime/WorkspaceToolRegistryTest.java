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

    /** 多能力项目必须按顺序接受 progress，全部接受后 final 才能结束。 */
    @Test
    void milestoneProgressControlsFinalCheckpoint() {
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("create then vote");
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
