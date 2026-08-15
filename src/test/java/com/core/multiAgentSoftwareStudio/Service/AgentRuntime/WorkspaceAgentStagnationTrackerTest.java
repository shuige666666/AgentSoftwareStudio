package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolCall;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceAgentStagnationTrackerTest {

    @TempDir
    Path projectRoot;

    @Test
    void stopsAfterThreeIdenticalRequestsWithoutAWrite() {
        WorkspaceAgentStagnationTracker tracker = new WorkspaceAgentStagnationTracker(
                new WorkspaceAgentLimitsConfig().getStagnation());
        WorkspaceAgentSession session = session();
        ToolCall call = new ToolCall("call", "read_files", "{\"paths\":[\"pom.xml\"]}");

        assertFalse(tracker.observe(call, "{\"files\":[]}", session).stop());
        assertFalse(tracker.observe(call, "{\"files\":[]}", session).stop());
        WorkspaceAgentStagnationTracker.Decision decision =
                tracker.observe(call, "{\"files\":[]}", session);

        assertTrue(decision.stop());
        assertTrue(decision.reason().contains("same tool request"));
    }

    @Test
    void stopsWhenSameVerificationFailureSurvivesSeparateEdits() {
        WorkspaceAgentStagnationTracker tracker = new WorkspaceAgentStagnationTracker(
                new WorkspaceAgentLimitsConfig().getStagnation());
        WorkspaceAgentSession session = session();
        ToolCall verify = new ToolCall("verify", "compile_main", "{}");
        String failure = "{\"passed\":false,\"output\":\"compilation failure\"}";

        session.recordWrite("src/main/java/demo/App.java");
        assertFalse(tracker.observe(verify, failure, session).stop());
        session.recordWrite("src/main/java/demo/App.java");
        WorkspaceAgentStagnationTracker.Decision decision = tracker.observe(verify, failure, session);

        assertTrue(decision.stop());
        assertTrue(decision.reason().contains("same verification failure"));
    }

    @Test
    void successfulVerificationIsNotMisclassifiedByZeroErrorSummary() {
        WorkspaceAgentStagnationTracker tracker = new WorkspaceAgentStagnationTracker(
                new WorkspaceAgentLimitsConfig().getStagnation());
        WorkspaceAgentSession session = session();
        ToolCall verify = new ToolCall("verify", "run_tests", "{}");
        String success = "{\"passed\":true,\"output\":\"Tests run: 5, Errors: 0\"}";

        session.recordWrite("src/main/java/demo/App.java");
        assertFalse(tracker.observe(verify, success, session).stop());
        session.recordWrite("src/main/java/demo/App.java");
        assertFalse(tracker.observe(verify, success, session).stop());
    }

    private WorkspaceAgentSession session() {
        return new WorkspaceAgentSession(projectRoot, WorkspaceAgentMode.REPAIR_IMPLEMENTATION,
                new SoftwareStudioWorkflowData("test"), message -> { }, Map.of(), Set.of());
    }
}
