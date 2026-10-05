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
import static org.mockito.ArgumentMatchers.eq;
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

    /** 中段提醒在续接会话中只出现一次，并要求先取得全套测试反馈。 */
    @Test
    void developmentRequestsVerificationWithRepairTurnsRemaining() {
        RecordingToolModel model = new RecordingToolModel(List.of(
                response("call-24", "list_files", "{}"),
                response("call-25", "submit_checkpoint", "{\"scope\":\"final\"}")));
        WorkspaceToolRegistry registry = mock(WorkspaceToolRegistry.class);
        when(registry.definitions(WorkspaceAgentMode.DEVELOPMENT)).thenReturn(List.of());
        when(registry.snapshotWorkspace(any())).thenReturn(new LinkedHashMap<>());
        when(registry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            if ("submit_checkpoint".equals(invocation.getArgument(0))) {
                ((WorkspaceAgentSession) invocation.getArgument(2)).complete("verified");
            }
            return "{}";
        });
        var data = new SoftwareStudioWorkflowData("demo");
        data.projectPath = projectRoot.toString();
        data.developmentModelTurnsUsed = 23;

        WorkspaceAgentRunResult result = runtime(model, registry).runDetailed(data,
                WorkspaceAgentMode.DEVELOPMENT, Set.of(), "resume", message -> { });

        assertTrue(result.completed());
        assertEquals(25, result.modelTurns());
        for (var history : model.histories) {
            assertEquals(1, history.stream().filter(message -> "user".equals(message.role())
                    && message.content().contains("Run a full compile and all current tests now")).count());
            assertTrue(history.get(0).content().contains("keep success-case fixtures within declared validation constraints"));
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

    /** 重复失败提示实际进入下一轮消息，且不会插在同一响应的多个工具结果之间。 */
    @Test
    void developmentDeliversRepeatedFailureAdviceAfterAllToolResponses() {
        var second = new ToolModelResponse(ToolChatMessage.assistant("", List.of(
                new ToolCall("test-2", "bash", "{\"command\":\"mvn test\"}"),
                new ToolCall("read-2", "read_files", "{\"paths\":[\"App.java\"]}"))), "tool_calls");
        var model = new RecordingToolModel(List.of(
                response("test-1", "bash", "{\"command\":\"mvn test\"}"), second,
                response("final", "submit_checkpoint", "{\"scope\":\"final\"}")));
        WorkspaceToolRegistry registry = mock(WorkspaceToolRegistry.class);
        when(registry.definitions(WorkspaceAgentMode.DEVELOPMENT)).thenReturn(List.of());
        when(registry.snapshotWorkspace(any())).thenReturn(new LinkedHashMap<>());
        when(registry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            WorkspaceAgentSession session = invocation.getArgument(2);
            if ("bash".equals(invocation.getArgument(0))) {
                session.recordWrite("App.java");
                return "{\"passed\":false,\"output\":\"[ERROR] AppTest.setUp:22 Status expected:<404> but was:<200>\"}";
            }
            if ("submit_checkpoint".equals(invocation.getArgument(0))) session.complete("verified");
            return "{}";
        });
        var data = new SoftwareStudioWorkflowData("demo");
        data.projectPath = projectRoot.toString();

        assertTrue(runtime(model, registry).runDetailed(data, WorkspaceAgentMode.DEVELOPMENT,
                Set.of(), "develop", message -> { }).completed());
        var history = model.histories.get(2);
        assertEquals("tool", history.get(history.size() - 2).role());
        assertEquals("user", history.get(history.size() - 1).role());
        assertTrue(history.get(history.size() - 1).content().contains("failing test setup"));
    }

    /** 模拟真实网络超时，证明重试压缩输入但不重新执行工具、不补发全局预算。 */
    @Test
    void retryReducesReadHistoryAndPreservesUnresolvedFailure() throws Exception {
        var histories = new ArrayList<List<ToolChatMessage>>();
        ToolCallingModel model = (messages, tools) -> {
            histories.add(List.copyOf(messages));
            if (histories.size() == 1) return response("read", "read_files", "{}");
            if (histories.size() == 2) throw new IllegalStateException("timeout", new HttpTimeoutException("timeout"));
            return response("final", "submit_checkpoint", "{\"scope\":\"final\"}");
        };
        WorkspaceToolRegistry registry = mock(WorkspaceToolRegistry.class);
        when(registry.definitions(WorkspaceAgentMode.DEVELOPMENT)).thenReturn(List.of());
        when(registry.snapshotWorkspace(any())).thenReturn(new LinkedHashMap<>());
        when(registry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            WorkspaceAgentSession session = invocation.getArgument(2);
            if ("read_files".equals(invocation.getArgument(0))) {
                session.recordVerificationFailure("AppTest: expected 404 but was 200");
                var result = new ObjectMapper().createObjectNode();
                result.putArray("files").addObject().put("path", "App.java").put("sha256", "hash")
                        .put("content", "large-source".repeat(1_000));
                return result.toString();
            }
            session.complete("verified");
            return "{\"status\":\"PASSED\"}";
        });
        var data = new SoftwareStudioWorkflowData("complete public goal");
        data.projectPath = projectRoot.toString();

        var result = runtime(model, registry).runDetailed(data, WorkspaceAgentMode.DEVELOPMENT,
                Set.of(), "complete public goal", message -> { });

        assertTrue(result.completed());
        assertEquals(3, result.modelTurns());
        assertEquals(2, result.toolCalls());
        assertTrue(WorkspaceAgentContextCompactor.estimatedTokens(histories.get(2))
                < WorkspaceAgentContextCompactor.estimatedTokens(histories.get(1)));
        assertEquals("complete public goal", histories.get(2).get(1).content());
        assertTrue(histories.get(2).get(2).content().contains("expected 404 but was 200"));
        verify(registry, times(1)).execute(eq("read_files"), anyString(), any());
    }

    private ToolModelResponse response(String id, String name, String arguments) {
        return new ToolModelResponse(
                ToolChatMessage.assistant("", List.of(new ToolCall(id, name, arguments))),
                "tool_calls");
    }

    /** 中途验证在完整工具轮次后返回真实反馈，模型无需先主动提交 checkpoint。 */
    @Test
    void platformValidationReachesSameSessionBeforeFurtherEdits() throws Exception {
        var model = new RecordingToolModel(List.of(response("edit", "apply_patch", "{}"),
                response("read", "read_files", "{}"), response("fix", "apply_patch", "{}"),
                response("final", "submit_checkpoint", "{\"scope\":\"final\"}")));
        var registry = mock(WorkspaceToolRegistry.class);
        when(registry.snapshotWorkspace(any())).thenReturn(new LinkedHashMap<>());
        when(registry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            WorkspaceAgentSession session = invocation.getArgument(2);
            if ("apply_patch".equals(invocation.getArgument(0))) session.recordWrite("App.java");
            if ("submit_checkpoint".equals(invocation.getArgument(0))) session.complete("verified");
            return "{}";
        });
        when(registry.validateProgress(any())).thenAnswer(invocation -> {
            WorkspaceAgentSession session = invocation.getArgument(0);
            session.recordCheckpoint("frozen-hash", true, false);
            session.recordAutomaticCheck();
            session.recordBehaviorFeedback("Public behavior link.create: expected 201 but was 200; sourceHash=frozen-hash");
            return session.unresolvedVerificationFailure();
        });
        var limits = new WorkspaceAgentLimitsConfig();
        limits.getValidation().setIntervalTurns(2);
        limits.getValidation().setQuietTurns(1);
        var auditConfig = new WorkspaceAgentAuditConfig();
        auditConfig.setEnabled(false);
        var runtime = new WorkspaceAgentRuntime(model, registry, new WorkspaceService(new SourceCodePathService()),
                new LlmUsageMetricsService(), limits, new ToolCallAuditService(new ObjectMapper(), auditConfig),
                new com.core.multiAgentSoftwareStudio.Service.Workflow.DevelopmentMilestoneService(new ObjectMapper()));
        var data = new SoftwareStudioWorkflowData("public requirements");
        data.projectPath = projectRoot.toString();
        assertTrue(runtime.runDetailed(data, WorkspaceAgentMode.DEVELOPMENT, Set.of(), "repair", ignored -> {}).completed());
        var history = model.histories.get(2);
        assertEquals("tool", history.get(history.size() - 2).role());
        assertEquals("read", history.get(history.size() - 2).toolCallId());
        assertEquals("user", history.getLast().role());
        assertTrue(history.getLast().content().contains("expected 201 but was 200"));
        verify(registry, times(1)).validateProgress(any());
    }

    /** 普通自测不能抹掉独立业务失败；未修改版本不重复触发自动验证。 */
    @Test
    void automaticValidationPreservesBusinessFailureAndSkipsUnchangedSource() {
        var data = new SoftwareStudioWorkflowData("public requirements");
        var session = new WorkspaceAgentSession(projectRoot, WorkspaceAgentMode.DEVELOPMENT,
                data, ignored -> {}, java.util.Map.of(), Set.of());
        var policy = new WorkspaceAgentLimitsConfig.ValidationPolicy();
        policy.setIntervalTurns(2);
        policy.setQuietTurns(1);
        session.incrementModelTurns();
        session.recordWrite("App.java");
        assertEquals(false, session.validationDue(policy, false));
        session.incrementModelTurns();
        assertTrue(session.validationDue(policy, false));
        session.recordCheckpoint("version1", true, false);
        session.recordBehaviorFeedback("independent: expected 404 but was 200");
        session.recordTestsPassed();
        assertTrue(session.unresolvedVerificationFailure().contains("expected 404 but was 200"));
        assertEquals(false, session.validationDue(policy, true));
        session.recordWrite("App.java");
        assertTrue(session.validationDue(policy, true));
    }

    /** 无工具的结束声明不能绕过反馈，会话继续接收真实失败而不是被文本宣布成功。 */
    @Test
    void textCompletionTriggersBoundedValidationBeforeContinuation() throws Exception {
        var model = new RecordingToolModel(List.of(new ToolModelResponse(
                ToolChatMessage.assistant("Done", List.of()), "stop"),
                response("final", "submit_checkpoint", "{\"scope\":\"final\"}")));
        var registry = mock(WorkspaceToolRegistry.class);
        when(registry.snapshotWorkspace(any())).thenReturn(new LinkedHashMap<>());
        when(registry.validateProgress(any())).thenAnswer(invocation -> {
            WorkspaceAgentSession session = invocation.getArgument(0);
            session.recordCheckpoint("current-source", false, false);
            session.recordAutomaticCheck();
            return "Public behavior expected 404 but was 200; sourceHash=current-source";
        });
        when(registry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            ((WorkspaceAgentSession) invocation.getArgument(2)).block("repair still needed");
            return "{}";
        });
        var data = new SoftwareStudioWorkflowData("public requirements");
        data.projectPath = projectRoot.toString();
        var result = runtime(model, registry).runDetailed(data, WorkspaceAgentMode.DEVELOPMENT,
                Set.of(), "repair", ignored -> {});
        assertEquals(false, result.completed());
        assertTrue(model.histories.get(1).stream().anyMatch(message -> "user".equals(message.role())
                && message.content().contains("expected 404 but was 200")));
        verify(registry, times(1)).validateProgress(any());
    }

    /** 模拟模型根据真实错误反馈重新提交；参数与补丁拒绝不结束会话，也不伪造验证通过。 */
    @Test
    void resubmitsAfterJsonAndEmptyHunkFailuresInTheSameDevelopmentSession() throws Exception {
        var mapper = new ObjectMapper();
        Path source = projectRoot.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        String original = "class App { int value = 1; }\n";
        Files.writeString(source, original, StandardCharsets.UTF_8);
        String patch = "*** Begin Patch\n*** Update File: src/main/java/demo/App.java\n@@\n"
                + "-class App { int value = 1; }\n+class App { int value = 2; }\n@@\n*** End Patch\n";
        String corrected = patch.replace("@@\n*** End Patch", "*** End Patch");
        var registry = new WorkspaceToolRegistry(mapper, null,
                new WorkspaceService(new SourceCodePathService()), null, new WorkspacePatchService(),
                null, null, new com.core.multiAgentSoftwareStudio.Service.Workflow.DevelopmentMilestoneService(mapper));
        List<List<ToolChatMessage>> histories = new ArrayList<>();
        ToolCallingModel model = (messages, definitions) -> {
            histories.add(List.copyOf(messages));
            int turn = histories.size();
            try {
                if (turn == 1) return response("bad-json", "apply_patch", "{\"patch\":\"*** Begin Patch\\n");
                ToolChatMessage feedback = messages.getLast();
                assertEquals("tool", feedback.role());
                var result = mapper.readTree(feedback.content());
                if (turn == 2) {
                    assertEquals("bad-json", feedback.toolCallId());
                    assertEquals("TOOL_ARGUMENTS_INVALID_JSON", result.path("error").asText());
                    assertEquals(original, Files.readString(source));
                    return response("empty-hunk", "apply_patch", mapper.writeValueAsString(java.util.Map.of("patch", patch)));
                }
                if (turn == 3) {
                    assertEquals("empty-hunk", feedback.toolCallId());
                    assertEquals("PATCH_CONTEXT", result.path("error").asText());
                    assertTrue(result.path("message").asText().contains("Remove the unused @@"));
                    assertEquals(original, Files.readString(source));
                    return response("corrected", "apply_patch", mapper.writeValueAsString(java.util.Map.of("patch", corrected)));
                }
                assertEquals(4, turn);
                assertEquals("corrected", feedback.toolCallId());
                assertTrue(result.path("ok").asBoolean());
                assertEquals("class App { int value = 2; }\n", Files.readString(source));
                // 本测试只检查编辑反馈链路；用正常停止工具结束，不能假装完成了最终验收。
                return response("stop", "report_blocker", "{\"reason\":\"Fixed mechanism replay ended before validation\"}");
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        };
        var data = new SoftwareStudioWorkflowData("Local mock feedback replay");
        data.projectPath = projectRoot.toString();
        var result = runtime(model, registry).runDetailed(data, WorkspaceAgentMode.DEVELOPMENT,
                Set.of(), "Inspect and repair the current source", ignored -> {});
        assertEquals(WorkspaceAgentStopReason.REPORTED_BLOCKER, result.stopReason());
        assertEquals(false, result.completed());
        assertEquals(false, data.success);
        assertEquals(4, result.modelTurns());
        assertEquals(4, result.toolCalls());
        assertEquals(List.of("src/main/java/demo/App.java"), result.changedFiles());
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
