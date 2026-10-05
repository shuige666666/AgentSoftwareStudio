package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentAuditConfig;
import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult;
import com.core.multiAgentSoftwareStudio.Model.Generation.ProjectProfile;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolCall;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolModelResponse;
import com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestoneState;
import com.core.multiAgentSoftwareStudio.Model.Workflow.SandboxExecutionResult;
import com.core.multiAgentSoftwareStudio.Service.Benchmark.BenchmarkBehaviorEvaluator;
import com.core.multiAgentSoftwareStudio.Service.Contract.ProjectProfileService;
import com.core.multiAgentSoftwareStudio.Service.Generation.DevelopmentMilestoneObjective;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.DevelopmentMilestoneService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.RunJournalService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workflow.VerificationResultService;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.core.multiAgentSoftwareStudio.Tool.DockerSandboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** 复现会话结束后才得到最终验收证据的真实续接顺序。 */
class WorkspaceContinuationFeedbackTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 正常压缩阈值下，续接第一请求也应携带保存的诊断；没有历史调用时同样适用。 */
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void restoredFeedbackReachesFirstRequestBelowCompactionThreshold(boolean withHistory) throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>\n", StandardCharsets.UTF_8);
        var data = data();
        var workspace = new WorkspaceService(new SourceCodePathService());
        var registry = new WorkspaceToolRegistry(mapper, null, workspace, null,
                new WorkspacePatchService(), null, null, new DevelopmentMilestoneService(mapper));
        var saved = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT, data,
                null, registry.snapshotWorkspace(root), Set.of());
        saved.messages().add(ToolChatMessage.system("system"));
        saved.messages().add(ToolChatMessage.user("old objective"));
        if (withHistory) {
            saved.messages().add(ToolChatMessage.assistant("", List.of(new ToolCall("read", "read_files", "{\"paths\":[\"pom.xml\"]}"))));
            saved.messages().add(ToolChatMessage.tool("read", registry.execute("read_files", "{\"paths\":[\"pom.xml\"]}", saved)));
        }
        saved.recordVerificationFailure("restored source assertion failure");
        saved.recordFinalFeedback("README.md is missing");
        saved.recordBehaviorFeedback("independent behavior expected 404 but was 500");
        WorkspaceDevelopmentContext.save(saved, registry.snapshotWorkspace(root));
        var limits = new WorkspaceAgentLimitsConfig();
        limits.getValidation().setEnabled(false);
        limits.getDevelopment().setMaxModelTurns(3);
        var audit = new WorkspaceAgentAuditConfig();
        audit.setEnabled(false);
        ToolCallingModel model = (messages, tools) -> {
            String input = messages.stream().map(ToolChatMessage::content).collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(input.contains("restored source assertion failure"));
            assertTrue(input.contains("README.md is missing"));
            assertTrue(input.contains("independent behavior expected 404 but was 500"));
            if (withHistory) assertTrue(input.contains("<project/>"));
            assertEquals(3, data.developmentModelTurnsUsed);
            return new ToolModelResponse(ToolChatMessage.assistant("", List.of(new ToolCall("stop", "report_blocker",
                    "{\"reason\":\"local resume feedback replay complete\"}"))), "tool_calls");
        };
        var runtime = new WorkspaceAgentRuntime(model, registry, workspace, new LlmUsageMetricsService(), limits,
                new ToolCallAuditService(mapper, audit), new DevelopmentMilestoneService(mapper));
        assertEquals(WorkspaceAgentStopReason.REPORTED_BLOCKER,
                runtime.runDetailed(data, WorkspaceAgentMode.DEVELOPMENT, Set.of(), "current complete goal", ignored -> { }).stopReason());
    }

    /** 外层最终验收的新异常和文档失败在续接压缩后仍应送到模型。 */
    @Test void finalAfterContextSaveSurvivesTheNextModelInput() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "<project/>\n", StandardCharsets.UTF_8);
        var data = data();
        var workspace = new WorkspaceService(new SourceCodePathService());
        var registry = new WorkspaceToolRegistry(mapper, null, workspace, null,
                new WorkspacePatchService(), null, null, new DevelopmentMilestoneService(mapper));
        var saved = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT, data,
                null, registry.snapshotWorkspace(root), Set.of());
        saved.messages().add(ToolChatMessage.system("system"));
        saved.messages().add(ToolChatMessage.user("old objective"));
        saved.recordCheckpoint("previous-progress", true, false);
        saved.messages().add(ToolChatMessage.assistant("old large observation ".repeat(300), List.of()));
        WorkspaceDevelopmentContext.save(saved, registry.snapshotWorkspace(root));

        // 先保存会话，再执行外层验收；这一顺序不能靠最后一次工具响应覆盖。
        var finalResult = checkpointService().verifyCandidate(data, ignored -> { });
        new DevelopmentMilestoneService(mapper).recordFailure(data, WorkspaceCheckpointFeedback.failureSummary(finalResult));
        assertFalse(finalResult.allowCompletion());
        var limits = new WorkspaceAgentLimitsConfig();
        limits.getContext().setMaxContextTokens(2000);
        limits.getContext().setMaxWorkingContextTokens(2000);
        limits.getContext().setCompactAtPercent(1);
        var audit = new WorkspaceAgentAuditConfig();
        audit.setEnabled(false);
        ToolCallingModel model = (messages, tools) -> {
            String input = messages.stream().map(ToolChatMessage::content).collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(input.contains("TemplateInputException"), "外层的新运行异常在压缩中丢失");
            assertTrue(input.contains("README.md is missing"), "外层的文档失败在压缩中丢失");
            assertTrue(input.contains(finalResult.sourceHash()));
            assertEquals(3, data.developmentModelTurnsUsed);
            return new ToolModelResponse(ToolChatMessage.assistant("", List.of(
                    new ToolCall("stop", "report_blocker", "{\"reason\":\"local mechanism replay finished\"}"))), "tool_calls");
        };
        var runtime = new WorkspaceAgentRuntime(model, registry, workspace, new LlmUsageMetricsService(), limits,
                new ToolCallAuditService(mapper, audit), new DevelopmentMilestoneService(mapper));
        var result = runtime.runDetailed(data, WorkspaceAgentMode.DEVELOPMENT, Set.of(),
                DevelopmentMilestoneObjective.renderSingle(data, true), ignored -> { });
        assertEquals(WorkspaceAgentStopReason.REPORTED_BLOCKER, result.stopReason());
        assertEquals(3, result.modelTurns());
        assertFalse(data.success);
    }

    /** 外部改源码后最终验收只保留新证据，不得把失效的历史拼接回新源码。 */
    @Test void changedSourceGetsNewFeedbackWithoutRestoringOldHistory() throws Exception {
        Files.writeString(root.resolve("pom.xml"), "before", StandardCharsets.UTF_8);
        var data = data();
        var registry = new WorkspaceToolRegistry(mapper, null, null, null, null, null, null, null);
        var saved = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT, data,
                null, registry.snapshotWorkspace(root), Set.of());
        saved.messages().add(ToolChatMessage.system("system"));
        saved.messages().add(ToolChatMessage.user("objective"));
        saved.messages().add(ToolChatMessage.assistant("OBSOLETE_HISTORY", List.of()));
        WorkspaceDevelopmentContext.save(saved, registry.snapshotWorkspace(root));
        Files.writeString(root.resolve("pom.xml"), "<project/>\n", StandardCharsets.UTF_8);
        checkpointService().verifyCandidate(data, ignored -> { });
        var resumed = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT, data,
                null, registry.snapshotWorkspace(root), Set.of());
        WorkspaceDevelopmentContext.restore(resumed);
        assertTrue(resumed.behaviorVerificationFailure().contains("TemplateInputException"));
        assertTrue(resumed.finalVerificationFailure().contains("README.md"));
        assertFalse(resumed.messages().stream().anyMatch(message -> message.content().contains("OBSOLETE_HISTORY")));
        assertEquals(2, resumed.modelTurns());
        assertFalse(resumed.finalCheckpointPassed());
        assertFalse(resumed.completed());
    }

    private SoftwareStudioWorkflowData data() {
        var data = new SoftwareStudioWorkflowData("generic MVC application");
        data.projectPath = root.toString();
        data.projectProfile = ProjectProfile.java17SpringBoot();
        data.behaviorCheckId = "blog-form-behavior-v1";
        data.developmentModelTurnsUsed = 2;
        data.developmentToolCallsUsed = 2;
        data.milestoneState = new DevelopmentMilestoneState(3, List.of(), 2, 2, "");
        return data;
    }

    /** 使用真实 checkpoint 服务，只有隔离执行与公开行为响应由夹具提供。 */
    private WorkspaceCheckpointService checkpointService() {
        var sandbox = mock(DockerSandboxService.class);
        when(sandbox.runCheckpointMaven(any(), anyString())).thenReturn(new SandboxExecutionResult("mvn", 0,
                "Tests run: 1, Failures: 0, Errors: 0, Skipped: 0\nBUILD SUCCESS", 1, false, null));
        var behavior = mock(BenchmarkBehaviorEvaluator.class);
        when(behavior.evaluate(anyString(), anyString())).thenReturn(new BenchmarkBehaviorResult("FAIL", List.of(),
                "Runtime exception: TemplateInputException; report=runtime.log"));
        var profiles = mock(ProjectProfileService.class);
        when(profiles.evaluate(any(), any(), any(), any())).thenReturn(
                com.core.multiAgentSoftwareStudio.Model.Generation.ProjectQualityPolicyResult.empty());
        return new WorkspaceCheckpointService(sandbox, new VerificationResultService(), profiles,
                new WorkspaceService(new SourceCodePathService()), new RunJournalService(), mapper, behavior);
    }
}
