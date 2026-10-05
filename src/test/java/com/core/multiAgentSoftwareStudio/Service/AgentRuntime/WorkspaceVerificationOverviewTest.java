package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentAuditConfig;
import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorFinding;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolCall;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolModelResponse;
import com.core.multiAgentSoftwareStudio.Model.Workflow.*;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.DevelopmentMilestoneService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** 区分同版本测试失败和有限业务成功，不把历史绿色升级为完成状态。 */
class WorkspaceVerificationOverviewTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 压缩后应同时保留失败诊断和同一快照的业务成功，不重复展开成功页面。 */
    @Test
    void passingBehaviorSurvivesCompactionAlongsideFailingTests() throws Exception {
        var session = session(Map.of("src/Widget.java", "class Widget {}"));
        var checkpoint = checkpoint("progress", "checked-hash", false, passingBehavior());
        WorkspaceCheckpointFeedback.record(session, checkpoint);
        session.recordFinalFeedback("README.md is missing");
        session.messages().add(ToolChatMessage.assistant("", List.of(new ToolCall("read", "read_files", "{}"))));
        session.messages().add(ToolChatMessage.tool("read", "{\"files\":[{\"path\":\"src/Widget.java\",\"content\":\"class Widget {}\"}]}"));
        session.messages().add(ToolChatMessage.user("Platform validation of current candidate: " + mapper.writeValueAsString(checkpoint)));
        String input = compactedInput(session);
        assertTrue(input.contains("build=PASSED tests=FAILED"));
        assertTrue(input.contains("testsRun=16 failures=6 errors=0"));
        assertTrue(input.contains("behavior=PASS passed=2 unresolved=0"));
        assertTrue(input.contains("sourceHash=checked-hash"));
        assertTrue(input.contains("scope=progress"));
        assertTrue(input.contains("Status expected:<200> but was:<404>"));
        assertTrue(input.contains("README.md is missing"));
        assertTrue(input.contains("class Widget {}"));
        assertFalse(input.contains("passing-page-body"));
        assertFalse(session.completed());
        assertFalse(session.finalCheckpointPassed());
    }

    /** 写入后旧结果必须标为历史；自测不能替代正式范围，下一次正式检查才更新对照。 */
    @Test
    void writesInvalidateOverviewAndFormalRecheckReplacesIt() {
        var session = session(Map.of());
        WorkspaceCheckpointFeedback.record(session, checkpoint("progress", "old-hash", false, passingBehavior()));
        session.recordFinalFeedback("Final README needs correction");
        session.recordWrite("src/Widget.java");
        session.recordTestsPassed();
        String historical = compactedInput(session);
        assertTrue(historical.contains("currentWriteVersion=false"));
        assertTrue(historical.contains("sourceHash=old-hash"));
        assertTrue(historical.contains("tests=FAILED"));
        assertTrue(historical.contains("Final README needs correction"));
        WorkspaceCheckpointFeedback.record(session, checkpoint("progress", "new-hash", true,
                BenchmarkBehaviorResult.notRun("Independent check not executed")));
        String current = compactedInput(session);
        assertTrue(current.contains("currentWriteVersion=true"));
        assertTrue(current.contains("sourceHash=new-hash"));
        assertTrue(current.contains("build=PASSED tests=PASSED"));
        assertTrue(current.contains("behavior=NOT_RUN passed=0 unresolved=0"));
        assertTrue(current.contains("Final README needs correction"));
        assertFalse(current.contains("sourceHash=old-hash"));
        assertFalse(session.completed());
    }

    /** 未注册或未执行的业务检查不能因测试状态而显示为业务成功。 */
    @Test
    void missingCoverageRemainsExplicit() {
        var session = session(Map.of());
        for (var behavior : List.of(BenchmarkBehaviorResult.notApplicable(), BenchmarkBehaviorResult.notRun("Not run"))) {
            WorkspaceCheckpointFeedback.record(session, checkpoint("progress", "checked-hash", false, behavior));
            String input = compactedInput(session);
            assertTrue(input.contains("behavior=" + behavior.status() + " passed=0 unresolved=0"));
            assertFalse(input.contains("behavior=PASS"));
        }
    }

    /** 没有历史工具轮次时，保存的对照也应进入正常阈值下的首个真实模型请求。 */
    @Test
    void resumedFirstRequestReceivesOverviewWithoutRestoringAcceptance() throws Exception {
        assertResumedFirstRequest(false);
    }

    /** 只有通过对照、没有失败或工具历史时，也必须在首请求重建已保存的事实。 */
    @Test
    void greenOverviewAloneTriggersFirstResumeSummary() throws Exception {
        assertResumedFirstRequest(true);
    }

    /** 两种保存状态走相同运行入口，均不恢复验收或完成标志。 */
    private void assertResumedFirstRequest(boolean testsPassed) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>\n", StandardCharsets.UTF_8);
        var data = data();
        var workspace = new WorkspaceService(new SourceCodePathService());
        var registry = new WorkspaceToolRegistry(mapper, null, workspace, null,
                new WorkspacePatchService(), null, null, new DevelopmentMilestoneService(mapper));
        var saved = session(registry.snapshotWorkspace(root), data);
        WorkspaceCheckpointFeedback.record(saved, checkpoint("progress", "checked-hash", testsPassed, passingBehavior()));
        WorkspaceDevelopmentContext.save(saved, registry.snapshotWorkspace(root));
        var limits = new WorkspaceAgentLimitsConfig();
        limits.getValidation().setEnabled(false);
        limits.getDevelopment().setMaxModelTurns(3);
        var audit = new WorkspaceAgentAuditConfig();
        audit.setEnabled(false);
        ToolCallingModel model = (messages, tools) -> {
            String input = messages.stream().map(ToolChatMessage::content).collect(Collectors.joining("\n"));
            assertTrue(input.contains("build=PASSED tests=" + (testsPassed ? "PASSED" : "FAILED")));
            assertTrue(input.contains("behavior=PASS passed=2 unresolved=0"));
            assertTrue(input.contains("sourceHash=checked-hash"));
            assertEquals(3, data.developmentModelTurnsUsed);
            return new ToolModelResponse(ToolChatMessage.assistant("", List.of(new ToolCall("stop", "report_blocker",
                    "{\"reason\":\"local verification overview replay complete\"}"))), "tool_calls");
        };
        var runtime = new WorkspaceAgentRuntime(model, registry, workspace, new LlmUsageMetricsService(), limits,
                new ToolCallAuditService(mapper, audit), new DevelopmentMilestoneService(mapper));
        var result = runtime.runDetailed(data, WorkspaceAgentMode.DEVELOPMENT, Set.of(), "current goal", ignored -> { });
        assertEquals(WorkspaceAgentStopReason.REPORTED_BLOCKER, result.stopReason());
        assertFalse(result.completed());
        assertFalse(data.success);
    }

    /** 旧v2没有结果对照字段，读取仍保留诊断且不改写元数据或恢复通过状态。 */
    @Test
    void olderV2RemainsReadOnlyAndDoesNotInventGreenEvidence() throws Exception {
        var saved = session(Map.of("src/Widget.java", "class Widget {}"));
        WorkspaceCheckpointFeedback.record(saved, checkpoint("progress", "old-hash", false, passingBehavior()));
        WorkspaceDevelopmentContext.save(saved, saved.baselineFiles());
        var file = root.resolve(".software-studio/development-context.json");
        var json = (ObjectNode) mapper.readTree(file.toFile());
        ((ObjectNode) json.path("verificationFeedback")).remove("checkpointObservation");
        Files.writeString(file, mapper.writeValueAsString(json), StandardCharsets.UTF_8);
        byte[] original = Files.readAllBytes(file);
        var resumed = session(saved.baselineFiles());
        WorkspaceDevelopmentContext.restore(resumed);
        String input = compactedInput(resumed);
        assertTrue(input.contains("Status expected:<200> but was:<404>"));
        assertFalse(input.contains("behavior=PASS"));
        assertFalse(resumed.compileCurrent());
        assertFalse(resumed.testsCurrent());
        assertFalse(resumed.finalCheckpointPassed());
        assertEquals(2, resumed.modelTurns());
        assertArrayEquals(original, Files.readAllBytes(file));
    }

    /** 使用真实压缩入口，不依赖读取后的完整checkpoint消息仍被保留。 */
    private String compactedInput(WorkspaceAgentSession session) {
        new WorkspaceAgentContextCompactor(new WorkspaceAgentLimitsConfig().getContext()).compactForResume(session, 34);
        return session.messages().stream().map(ToolChatMessage::content).collect(Collectors.joining("\n"));
    }

    private WorkspaceAgentSession session(Map<String, String> files) { return session(files, data()); }

    private WorkspaceAgentSession session(Map<String, String> files, SoftwareStudioWorkflowData data) {
        var session = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT, data, null, files, Set.of());
        session.messages().add(ToolChatMessage.system("system"));
        session.messages().add(ToolChatMessage.user("current goal"));
        return session;
    }

    private SoftwareStudioWorkflowData data() {
        var data = new SoftwareStudioWorkflowData("generic REST application");
        data.projectPath = root.toString();
        data.developmentModelTurnsUsed = 2;
        data.developmentToolCallsUsed = 2;
        return data;
    }

    /** 成功正文故意较长，用来确认保留的是受检事实而不是页面内容。 */
    private BenchmarkBehaviorResult passingBehavior() {
        return new BenchmarkBehaviorResult("PASS", List.of(
                new BenchmarkBehaviorFinding("create", true, "passing-page-body".repeat(400), "PASS", "POST /widgets", "201"),
                new BenchmarkBehaviorFinding("read", true, "HTTP 200", "PASS", "GET /widgets/1", "200")), null);
    }

    private WorkspaceCheckpointService.CheckpointResult checkpoint(String scope, String hash, boolean testsPassed,
                                                                  BenchmarkBehaviorResult behavior) {
        var build = new VerificationStepResult(VerificationStage.BUILD, VerificationStatus.PASSED, FailureKind.NONE,
                "mvn compile", 0, 1, TestSummary.empty(), "BUILD SUCCESS");
        var test = new VerificationStepResult(VerificationStage.TEST,
                testsPassed ? VerificationStatus.PASSED : VerificationStatus.FAILED,
                testsPassed ? FailureKind.NONE : FailureKind.TEST_ASSERTION, "mvn test", testsPassed ? 0 : 1,
                1, new TestSummary(16, testsPassed ? 0 : 6, 0, 0, true), "");
        return new WorkspaceCheckpointService.CheckpointResult(scope, testsPassed ? "PASSED" : "FAILED", hash,
                "snapshot", "report.json", build, test, true, List.of(),
                testsPassed ? "" : "WidgetApiTest.results: Status expected:<200> but was:<404>", false, false, behavior);
    }
}
