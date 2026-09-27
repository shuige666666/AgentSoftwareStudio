package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentAuditConfig;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolCall;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolDefinition;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolModelResponse;
import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.core.multiAgentSoftwareStudio.Service.Source.SourceCodePathService;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.core.multiAgentSoftwareStudio.Service.Workspace.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceAgentRuntimeTest {

    @TempDir
    Path projectRoot;

    @Test
    void sendsToolResultBackWithMatchingCallIdBeforeCompletion() throws Exception {
        Path source = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}", StandardCharsets.UTF_8);

        RecordingToolModel model = new RecordingToolModel(List.of(
                response("call-1", "list_files", "{}"),
                response("call-2", "complete_stage", "{\"summary\":\"verified\"}")));
        WorkspaceToolRegistry registry = mock(WorkspaceToolRegistry.class);
        when(registry.definitions()).thenReturn(List.of());
        when(registry.snapshotWorkspace(any())).thenReturn(new LinkedHashMap<>(
                java.util.Map.of("src/main/java/demo/App.java", "class App {}")));
        when(registry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            WorkspaceAgentSession session = invocation.getArgument(2);
            if ("complete_stage".equals(name)) {
                session.complete("verified");
                return "{\"accepted\":true}";
            }
            return "{\"files\":[\"src/main/java/demo/App.java\"]}";
        });

        WorkspaceAgentRuntime runtime = runtime(model, registry);
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.projectPath = projectRoot.toString();

        assertTrue(runtime.run(data, WorkspaceAgentMode.IMPLEMENT, Set.of(), "implement", message -> { }));
        assertEquals(2, model.histories.size());
        ToolChatMessage feedback = model.histories.get(1).getLast();
        assertEquals("tool", feedback.role());
        assertEquals("call-1", feedback.toolCallId());
        assertEquals(1, data.codes.size());
    }

    @Test
    void developmentRepairsProductionAfterTestFailureInSameSession() throws Exception {
        RecordingToolModel model = new RecordingToolModel(List.of(
                response("call-1", "apply_patch", "{\"patch\":\"add test\"}"),
                response("call-2", "submit_checkpoint", "{\"scope\":\"progress\",\"summary\":\"test\"}"),
                response("call-3", "apply_patch", "{\"patch\":\"fix production\"}"),
                response("call-4", "submit_checkpoint", "{\"scope\":\"final\",\"summary\":\"done\"}")));
        WorkspaceToolRegistry registry = mock(WorkspaceToolRegistry.class);
        when(registry.definitions(WorkspaceAgentMode.DEVELOPMENT)).thenReturn(List.of());
        when(registry.snapshotWorkspace(any())).thenReturn(new LinkedHashMap<>());
        when(registry.actualChangedFiles(any())).thenReturn(List.of());
        when(registry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            String arguments = invocation.getArgument(1);
            WorkspaceAgentSession session = invocation.getArgument(2);
            if ("apply_patch".equals(name) && arguments.contains("add test")) {
                Path test = projectRoot.resolve("src/test/java/demo/AppTest.java");
                Files.createDirectories(test.getParent());
                Files.writeString(test, "class AppTest {}", StandardCharsets.UTF_8);
                session.recordWrite("src/test/java/demo/AppTest.java");
                return "{\"applied\":1}";
            }
            if ("apply_patch".equals(name)) {
                Path source = projectRoot.resolve("src/main/java/demo/App.java");
                Files.createDirectories(source.getParent());
                Files.writeString(source, "class App {}", StandardCharsets.UTF_8);
                session.recordWrite("src/main/java/demo/App.java");
                return "{\"applied\":1}";
            }
            if (arguments.contains("progress")) return "{\"status\":\"FAILED\",\"failureOutput\":\"assertion failed\"}";
            session.complete("final checkpoint passed");
            session.workflowData().success = true;
            return "{\"status\":\"PASSED\"}";
        });
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.projectPath = projectRoot.toString();

        WorkspaceAgentRunResult result = runtime(model, registry).runDetailed(
                data, WorkspaceAgentMode.DEVELOPMENT, Set.of(), "implement", message -> { });

        assertTrue(result.completed());
        assertTrue(data.success);
        assertEquals(4, model.histories.size());
        assertTrue(model.histories.get(2).getLast().content().contains("assertion failed"));
        assertTrue(data.codes.stream().anyMatch(code -> code.filename().endsWith("AppTest.java")));
        assertTrue(data.codes.stream().anyMatch(code -> code.filename().endsWith("App.java")));
    }

    @Test
    void defaultImplementationSessionCanContinueBeyondEightModelTurns() throws Exception {
        List<ToolModelResponse> responses = new ArrayList<>();
        for (int index = 1; index <= 9; index++) {
            responses.add(response("call-" + index, "list_files", "{\"path\":\"src/" + index + "\"}"));
        }
        responses.add(response("call-10", "complete_stage", "{\"summary\":\"verified\"}"));
        RecordingToolModel model = new RecordingToolModel(responses);
        WorkspaceToolRegistry registry = mock(WorkspaceToolRegistry.class);
        when(registry.definitions()).thenReturn(List.of());
        when(registry.snapshotWorkspace(any())).thenReturn(new LinkedHashMap<>());
        when(registry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            WorkspaceAgentSession session = invocation.getArgument(2);
            if ("complete_stage".equals(invocation.getArgument(0))) {
                session.complete("verified");
            }
            return invocation.getArgument(1);
        });
        WorkspaceAgentRuntime runtime = runtime(model, registry);
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.projectPath = projectRoot.toString();

        WorkspaceAgentRunResult result = runtime.runDetailed(
                data, WorkspaceAgentMode.IMPLEMENT, Set.of(), "implement", message -> { });

        assertTrue(result.completed());
        assertEquals(10, result.modelTurns());
        assertEquals(WorkspaceAgentStopReason.COMPLETED, result.stopReason());
    }

    @Test
    void incompleteImplementationPreservesCandidateForOuterRepair() throws Exception {
        RecordingToolModel model = new RecordingToolModel(List.of(
                response("call-1", "apply_edits", "{}"),
                response("call-2", "report_blocker", "{\"reason\":\"needs outer repair\"}")));
        WorkspaceToolRegistry registry = mock(WorkspaceToolRegistry.class);
        when(registry.definitions()).thenReturn(List.of());
        when(registry.snapshotWorkspace(any())).thenReturn(new LinkedHashMap<>());
        when(registry.actualChangedFiles(any())).thenReturn(List.of("src/main/java/demo/App.java"));
        when(registry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            WorkspaceAgentSession session = invocation.getArgument(2);
            if ("apply_edits".equals(name)) {
                Path source = projectRoot.resolve("src/main/java/demo/App.java");
                Files.createDirectories(source.getParent());
                Files.writeString(source, "class App {}", StandardCharsets.UTF_8);
                session.recordWrite("src/main/java/demo/App.java");
            } else {
                session.block("needs outer repair");
            }
            return "{}";
        });
        WorkspaceAgentRuntime runtime = runtime(model, registry);
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.projectPath = projectRoot.toString();

        WorkspaceAgentRunResult result = runtime.runDetailed(
                data, WorkspaceAgentMode.IMPLEMENT, Set.of(), "implement", message -> { });

        assertEquals(WorkspaceAgentStopReason.REPORTED_BLOCKER, result.stopReason());
        assertEquals(1, data.codes.size());
        assertEquals(List.of("src/main/java/demo/App.java"), data.repairCandidateChangedFiles);
        verify(registry, never()).restoreBaseline(any());
    }

    @Test
    void incompleteTestAuthorPreservesTestCheckpointForOuterRepair() throws Exception {
        RecordingToolModel model = new RecordingToolModel(List.of(
                response("call-1", "apply_edits", "{}"),
                response("call-2", "report_blocker", "{\"reason\":\"production Spring wiring failure\"}")));
        WorkspaceToolRegistry registry = mock(WorkspaceToolRegistry.class);
        when(registry.definitions()).thenReturn(List.of());
        when(registry.snapshotWorkspace(any())).thenReturn(new LinkedHashMap<>());
        when(registry.actualChangedFiles(any())).thenReturn(List.of("src/test/java/demo/AppTest.java"));
        when(registry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            WorkspaceAgentSession session = invocation.getArgument(2);
            if ("apply_edits".equals(name)) {
                Path test = projectRoot.resolve("src/test/java/demo/AppTest.java");
                Files.createDirectories(test.getParent());
                Files.writeString(test, "class AppTest {}", StandardCharsets.UTF_8);
                session.recordWrite("src/test/java/demo/AppTest.java");
            } else {
                session.block("production Spring wiring failure");
            }
            return "{}";
        });
        WorkspaceAgentRuntime runtime = runtime(model, registry);
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.projectPath = projectRoot.toString();

        WorkspaceAgentRunResult result = runtime.runDetailed(
                data, WorkspaceAgentMode.TEST, Set.of(), "write tests", message -> { });

        assertEquals(WorkspaceAgentStopReason.REPORTED_BLOCKER, result.stopReason());
        assertEquals(List.of("src/test/java/demo/AppTest.java"), data.repairCandidateChangedFiles);
        assertTrue(data.codes.stream().anyMatch(code -> code.filename().endsWith("AppTest.java")));
        verify(registry, never()).restoreBaseline(any());
    }

    @Test
    void defaultRepairSessionCanContinueBeyondTheFormerFourTurnLimit() {
        List<ToolModelResponse> responses = new ArrayList<>();
        for (int index = 1; index <= 6; index++) {
            responses.add(response("call-" + index, "list_files", "{\"path\":\"src/" + index + "\"}"));
        }
        responses.add(response("call-7", "complete_stage", "{\"summary\":\"verified\"}"));
        RecordingToolModel model = new RecordingToolModel(responses);
        WorkspaceToolRegistry registry = mock(WorkspaceToolRegistry.class);
        when(registry.definitions()).thenReturn(List.of());
        when(registry.snapshotWorkspace(any())).thenReturn(new LinkedHashMap<>());
        when(registry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            WorkspaceAgentSession session = invocation.getArgument(2);
            if ("complete_stage".equals(invocation.getArgument(0))) {
                session.complete("verified");
            }
            return invocation.getArgument(1);
        });
        WorkspaceAgentRuntime runtime = runtime(model, registry);
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.projectPath = projectRoot.toString();

        WorkspaceAgentRunResult result = runtime.runDetailed(
                data, WorkspaceAgentMode.REPAIR_CONTRACT, Set.of(), "repair", message -> { });

        assertTrue(result.completed());
        assertEquals(7, result.modelTurns());
    }

    /** 续接会话从已消耗轮次继续计数，不能因重启而重置全局开发预算。 */
    @Test
    void resumedDevelopmentUsesRemainingGlobalTurns() {
        RecordingToolModel model = new RecordingToolModel(List.of(
                response("call-36", "submit_checkpoint", "{\"scope\":\"final\",\"summary\":\"done\"}")));
        WorkspaceToolRegistry registry = mock(WorkspaceToolRegistry.class);
        when(registry.definitions(WorkspaceAgentMode.DEVELOPMENT)).thenReturn(List.of());
        when(registry.snapshotWorkspace(any())).thenReturn(new LinkedHashMap<>());
        when(registry.actualChangedFiles(any())).thenReturn(List.of());
        when(registry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            WorkspaceAgentSession session = invocation.getArgument(2);
            session.complete("verified");
            return "{\"status\":\"PASSED\"}";
        });
        var data = new SoftwareStudioWorkflowData("demo");
        data.projectPath = projectRoot.toString();
        data.developmentModelTurnsUsed = 35;
        data.developmentToolCallsUsed = 107;

        WorkspaceAgentRunResult result = runtime(model, registry).runDetailed(data,
                WorkspaceAgentMode.DEVELOPMENT, Set.of(), "resume", message -> { });

        assertTrue(result.completed());
        assertEquals(36, result.modelTurns());
        assertEquals(108, result.toolCalls());
        assertEquals(1, model.histories.size());
    }

    /** 预算收尾提醒只注入一次，并在续接会话中沿用已消耗轮次。 */
    @Test
    void developmentWarnsOnceBeforeGlobalTurnLimit() {
        RecordingToolModel model = new RecordingToolModel(List.of(
                response("call-30", "list_files", "{}"),
                response("call-31", "submit_checkpoint", "{\"scope\":\"final\"}")));
        WorkspaceToolRegistry registry = mock(WorkspaceToolRegistry.class);
        when(registry.definitions(WorkspaceAgentMode.DEVELOPMENT)).thenReturn(List.of());
        when(registry.snapshotWorkspace(any())).thenReturn(new LinkedHashMap<>());
        when(registry.actualChangedFiles(any())).thenReturn(List.of());
        when(registry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            if ("submit_checkpoint".equals(invocation.getArgument(0))) {
                ((WorkspaceAgentSession) invocation.getArgument(2)).complete("verified");
            }
            return "{}";
        });
        var data = new SoftwareStudioWorkflowData("demo");
        data.projectPath = projectRoot.toString();
        data.developmentModelTurnsUsed = 29;

        WorkspaceAgentRunResult result = runtime(model, registry).runDetailed(data,
                WorkspaceAgentMode.DEVELOPMENT, Set.of(), "resume", message -> { });

        assertTrue(result.completed());
        assertEquals(31, result.modelTurns());
        for (var history : model.histories) {
            assertEquals(1, history.stream().filter(message -> "user".equals(message.role())
                    && message.content().contains("About 7 model turns remain")).count());
        }
    }

    /** 超时重试沿用原消息并消耗剩余全局轮次，成功响应仍可继续交付。 */
    @Test
    void developmentRetriesOneTimeoutWithinGlobalBudget() {
        ToolCallingModel model = mock(ToolCallingModel.class);
        when(model.generateWithTools(any(), any()))
                .thenThrow(new IllegalStateException("request timed out",
                        new HttpTimeoutException("request timed out")))
                .thenReturn(response("call-36", "submit_checkpoint", "{\"scope\":\"final\"}"));
        WorkspaceToolRegistry registry = mock(WorkspaceToolRegistry.class);
        when(registry.definitions(WorkspaceAgentMode.DEVELOPMENT)).thenReturn(List.of());
        when(registry.snapshotWorkspace(any())).thenReturn(new LinkedHashMap<>());
        when(registry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            ((WorkspaceAgentSession) invocation.getArgument(2)).complete("verified");
            return "{\"status\":\"PASSED\"}";
        });
        var data = new SoftwareStudioWorkflowData("demo");
        data.projectPath = projectRoot.toString();
        data.developmentModelTurnsUsed = 34;

        WorkspaceAgentRunResult result = runtime(model, registry).runDetailed(data,
                WorkspaceAgentMode.DEVELOPMENT, Set.of(), "resume", message -> { });

        assertTrue(result.completed());
        assertEquals(36, result.modelTurns());
        assertEquals(1, result.toolCalls());
        verify(model, times(2)).generateWithTools(any(), any());
    }

    /** 连续超时必须停止，不能绕过全局轮次或无限重复计费。 */
    @Test
    void developmentStopsAfterSecondConsecutiveTimeout() {
        ToolCallingModel model = mock(ToolCallingModel.class);
        when(model.generateWithTools(any(), any()))
                .thenThrow(new IllegalStateException("request timed out",
                        new HttpTimeoutException("request timed out")));
        WorkspaceToolRegistry registry = mock(WorkspaceToolRegistry.class);
        when(registry.definitions(WorkspaceAgentMode.DEVELOPMENT)).thenReturn(List.of());
        when(registry.snapshotWorkspace(any())).thenReturn(new LinkedHashMap<>());
        when(registry.actualChangedFiles(any())).thenReturn(List.of());
        var data = new SoftwareStudioWorkflowData("demo");
        data.projectPath = projectRoot.toString();
        data.developmentModelTurnsUsed = 34;

        WorkspaceAgentRunResult result = runtime(model, registry).runDetailed(data,
                WorkspaceAgentMode.DEVELOPMENT, Set.of(), "resume", message -> { });

        assertEquals(WorkspaceAgentStopReason.MODEL_OR_RUNTIME_ERROR, result.stopReason());
        assertEquals(36, result.modelTurns());
        verify(model, times(2)).generateWithTools(any(), any());
    }

    private ToolModelResponse response(String id, String name, String arguments) {
        return new ToolModelResponse(
                ToolChatMessage.assistant("", List.of(new ToolCall(id, name, arguments))),
                "tool_calls");
    }

    private WorkspaceAgentRuntime runtime(ToolCallingModel model, WorkspaceToolRegistry registry) {
        WorkspaceAgentAuditConfig auditConfig = new WorkspaceAgentAuditConfig();
        auditConfig.setEnabled(false);
        return new WorkspaceAgentRuntime(
                model,
                registry,
                new WorkspaceService(new SourceCodePathService()),
                new LlmUsageMetricsService(),
                new WorkspaceAgentLimitsConfig(),
                new ToolCallAuditService(new ObjectMapper(), auditConfig),
                new com.core.multiAgentSoftwareStudio.Service.Workflow.DevelopmentMilestoneService(new ObjectMapper()));
    }

    private static final class RecordingToolModel implements ToolCallingModel {
        private final List<ToolModelResponse> responses;
        private final List<List<ToolChatMessage>> histories = new ArrayList<>();
        private int index;

        private RecordingToolModel(List<ToolModelResponse> responses) {
            this.responses = responses;
        }

        @Override
        public ToolModelResponse generateWithTools(
                List<ToolChatMessage> messages,
                List<ToolDefinition> tools) {
            histories.add(List.copyOf(messages));
            return responses.get(index++);
        }
    }
}
