package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolCall;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceAgentContextCompactorTest {

    @TempDir
    Path projectRoot;

    @Test
    void keepsOriginalObjectiveAndThreeRecentCompleteToolRounds() {
        WorkspaceAgentSession session = new WorkspaceAgentSession(
                projectRoot, WorkspaceAgentMode.REPAIR_IMPLEMENTATION,
                new SoftwareStudioWorkflowData("test"), message -> { }, Map.of(), Set.of());
        session.messages().add(ToolChatMessage.system("system"));
        session.messages().add(ToolChatMessage.user("original objective"));
        for (int turn = 1; turn <= 7; turn++) {
            session.incrementModelTurns();
            session.messages().add(ToolChatMessage.assistant("assistant-" + turn,
                    List.of(new ToolCall("call-" + turn, "read_files", "{}"))));
            session.messages().add(ToolChatMessage.tool("call-" + turn, "result-" + turn));
        }

        WorkspaceAgentContextCompactor.Result result = new WorkspaceAgentContextCompactor(
                new WorkspaceAgentLimitsConfig().getContext()).compact(session, "read_files", "result-7");

        assertTrue(result.compacted());
        assertEquals("system", session.messages().get(0).content());
        assertEquals("original objective", session.messages().get(1).content());
        assertTrue(session.messages().get(2).content().contains("result-7"));
        assertFalse(session.messages().stream().anyMatch(message -> message.content().contains("assistant-4")));
        assertTrue(session.messages().stream().anyMatch(message -> message.content().contains("assistant-5")));
        assertEquals("call-7", session.messages().getLast().toolCallId());
    }
}
