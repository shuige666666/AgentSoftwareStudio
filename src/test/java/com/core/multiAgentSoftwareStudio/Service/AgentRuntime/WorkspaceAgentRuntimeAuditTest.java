package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentAuditConfig;
import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceAgentRuntimeAuditTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void runtimePersistsTheActualToolTimeline() throws Exception {
        Path project = temporaryRoot.resolve("candidate");
        Path source = project.resolve("src/main/java/demo/App.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "class App {}", StandardCharsets.UTF_8);

        ToolCallingModel model = new ToolCallingModel() {
            private int turn;

            @Override
            public ToolModelResponse generateWithTools(
                    List<ToolChatMessage> messages,
                    List<ToolDefinition> tools) {
                turn++;
                ToolCall call = turn == 1
                        ? new ToolCall("call-read", "list_files", "{}")
                        : new ToolCall("call-complete", "complete_stage", "{\"summary\":\"verified\"}");
                return new ToolModelResponse(ToolChatMessage.assistant("", List.of(call)), "tool_calls");
            }
        };
        WorkspaceToolRegistry registry = mock(WorkspaceToolRegistry.class);
        when(registry.definitions()).thenReturn(List.of());
        when(registry.snapshotWorkspace(any())).thenReturn(new LinkedHashMap<>(
                java.util.Map.of("src/main/java/demo/App.java", "class App {}")));
        when(registry.actualChangedFiles(any())).thenReturn(List.of());
        when(registry.execute(anyString(), anyString(), any())).thenAnswer(invocation -> {
            WorkspaceAgentSession session = invocation.getArgument(2);
            if ("complete_stage".equals(invocation.getArgument(0))) {
                session.complete("verified");
                return "{\"accepted\":true}";
            }
            return "{\"files\":[\"src/main/java/demo/App.java\"]}";
        });
        WorkspaceAgentAuditConfig auditConfig = new WorkspaceAgentAuditConfig();
        Path auditRoot = temporaryRoot.resolve("audit");
        auditConfig.setRootDirectory(auditRoot.toString());
        WorkspaceAgentRuntime runtime = new WorkspaceAgentRuntime(
                model,
                registry,
                new WorkspaceService(new SourceCodePathService()),
                new LlmUsageMetricsService(),
                new WorkspaceAgentLimitsConfig(),
                new ToolCallAuditService(new ObjectMapper(), auditConfig));
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.projectPath = project.toString();

        assertTrue(runtime.run(data, WorkspaceAgentMode.IMPLEMENT, Set.of(), "implement exactly", message -> { }));

        Path log = auditRoot.resolve("candidate/tool-calls.jsonl");
        String trace = Files.readString(log, StandardCharsets.UTF_8);
        assertTrue(trace.contains("\"event\":\"SESSION_STARTED\""));
        assertTrue(trace.contains("\"objective\":\"implement exactly\""));
        assertTrue(trace.contains("\"toolName\":\"list_files\""));
        assertTrue(trace.contains("\"toolName\":\"complete_stage\""));
        assertTrue(trace.contains("\"event\":\"SESSION_FINISHED\""));
    }
}
