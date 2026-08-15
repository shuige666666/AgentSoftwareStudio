package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentAuditConfig;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolCall;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCallAuditServiceTest {

    @TempDir
    Path temporaryRoot;

    @Test
    void writesInstructionToolArgumentsResultAndStopReasonToJsonLines() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        WorkspaceAgentAuditConfig config = new WorkspaceAgentAuditConfig();
        config.setRootDirectory(temporaryRoot.resolve("logs").toString());
        ToolCallAuditService service = new ToolCallAuditService(objectMapper, config);
        Path project = temporaryRoot.resolve("candidate-project");
        Files.createDirectories(project);
        SoftwareStudioWorkflowData data = new SoftwareStudioWorkflowData("test");
        data.projectPath = project.toString();
        WorkspaceAgentSession workspaceSession = new WorkspaceAgentSession(
                project,
                WorkspaceAgentMode.REPAIR_IMPLEMENTATION,
                data,
                message -> { },
                new LinkedHashMap<>(),
                Set.of());

        ToolCallAuditSession audit = service.startSession(
                workspaceSession,
                "system instruction",
                "repair instruction",
                16,
                48);
        workspaceSession.incrementModelTurns();
        workspaceSession.incrementToolCalls();
        ToolCall call = new ToolCall(
                "call-1",
                "search_code",
                "{\"query\":\"ExceptionHandler\",\"apiKey\":\"must-not-leak\"}");
        service.recordToolRequested(audit, workspaceSession, call);
        service.recordToolCompleted(
                audit,
                workspaceSession,
                call,
                "{\"passed\":false,\"output\":\"compiler failure\"}",
                25);
        service.finishSession(
                audit,
                workspaceSession,
                WorkspaceAgentStopReason.MODEL_TURN_LIMIT,
                List.of("src/main/java/demo/App.java"),
                "limit reached");

        List<String> lines = Files.readAllLines(audit.logPath(), StandardCharsets.UTF_8);
        assertEquals(4, lines.size());
        JsonNode started = objectMapper.readTree(lines.get(0));
        JsonNode requested = objectMapper.readTree(lines.get(1));
        JsonNode completed = objectMapper.readTree(lines.get(2));
        JsonNode finished = objectMapper.readTree(lines.get(3));
        assertEquals("repair instruction", started.path("objective").asText());
        assertEquals("search_code", requested.path("toolName").asText());
        assertEquals("ExceptionHandler", requested.path("arguments").path("query").asText());
        assertEquals("[REDACTED]", requested.path("arguments").path("apiKey").asText());
        assertEquals(25, completed.path("durationMillis").asInt());
        assertFalse(finished.path("completed").asBoolean());
        assertTrue(finished.path("changedFiles").toString().contains("App.java"));
    }
}
