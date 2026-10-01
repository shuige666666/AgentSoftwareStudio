package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Model.Tool.ToolCall;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceDevelopmentContextTest {
    @TempDir Path root;

    /** 同源码续接保留完整调用配对与错误，目标与预算仍使用本次平台状态。 */
    @Test void restoresPairedHistoryWithCurrentGoalAndBudget() throws Exception {
        var first = session(Map.of("src/A.java", "class A {}"), 2);
        first.messages().add(ToolChatMessage.assistant("", List.of(new ToolCall("p", "apply_patch", "{}"))));
        first.messages().add(ToolChatMessage.tool("p", "{\"error\":\"PATCH_FORMAT\"}"));
        first.messages().add(ToolChatMessage.user("Old task and closing reminder"));
        WorkspaceDevelopmentContext.save(first, first.baselineFiles());
        var resumed = session(first.baselineFiles(), 2);
        assertEquals(2, WorkspaceDevelopmentContext.restore(resumed));
        assertEquals("Current goal", resumed.messages().get(1).content());
        assertEquals("p", resumed.messages().get(3).toolCallId());
        assertTrue(resumed.messages().get(3).content().contains("PATCH_FORMAT"));
        assertEquals(2, resumed.modelTurns());
        assertEquals(2, resumed.toolCalls());
    }

    /** 外部改源码或累计轮次不一致时，旧历史不能被当成有效恢复材料。 */
    @Test void rejectsDifferentSourceOrBudget() throws Exception {
        var first = session(Map.of("src/A.java", "before"), 2);
        first.messages().add(ToolChatMessage.assistant("Observed", List.of()));
        WorkspaceDevelopmentContext.save(first, first.baselineFiles());
        assertEquals(0, WorkspaceDevelopmentContext.restore(session(Map.of("src/A.java", "after"), 2)));
        assertEquals(0, WorkspaceDevelopmentContext.restore(session(first.baselineFiles(), 3)));
    }

    /** 同会话内读后写的旧正文也需失效；工具响应身份和失败事实仍保留。 */
    @Test void removesStaleReadBodyWithoutBreakingPair() throws Exception {
        var first = session(Map.of("src/A.java", "after"), 2);
        first.messages().add(ToolChatMessage.assistant("", List.of(new ToolCall("r", "read_files", "{}"))));
        first.messages().add(ToolChatMessage.tool("r",
                "{\"files\":[{\"path\":\"src/A.java\",\"sha256\":\"oldhash\",\"content\":\"before\"}]}"));
        WorkspaceDevelopmentContext.save(first, first.baselineFiles());
        var resumed = session(first.baselineFiles(), 2);
        assertEquals(2, WorkspaceDevelopmentContext.restore(resumed));
        assertFalse(resumed.messages().get(3).content().contains("\"content\":\"before\""));
        assertTrue(resumed.messages().get(3).content().contains("source changed"));
    }

    /** 未响应的调用不能进入下一次会话。 */
    @Test void doesNotSaveUnpairedToolCall() throws Exception {
        var first = session(Map.of(), 2);
        first.messages().add(ToolChatMessage.assistant("", List.of(new ToolCall("p", "apply_patch", "{}"))));
        WorkspaceDevelopmentContext.save(first, first.baselineFiles());
        assertEquals(0, WorkspaceDevelopmentContext.restore(session(Map.of(), 2)));
    }

    /** 规划可能已重新裁决，恢复不得把旧契约正文覆盖当前平台目标。 */
    @Test void omitsHistoricalPlanningBody() throws Exception {
        var first = session(Map.of(), 2);
        first.messages().add(ToolChatMessage.assistant("", List.of(new ToolCall("plan", "read_planning", "{}"))));
        first.messages().add(ToolChatMessage.tool("plan",
                "{\"files\":[{\"path\":\".software-studio/contract.json\",\"content\":\"old contract\"}]}"));
        WorkspaceDevelopmentContext.save(first, first.baselineFiles());
        var resumed = session(Map.of(), 2);
        assertEquals(2, WorkspaceDevelopmentContext.restore(resumed));
        assertFalse(resumed.messages().get(3).content().contains("old contract"));
        assertEquals("Current goal", resumed.messages().get(1).content());
    }

    private WorkspaceAgentSession session(Map<String, String> files, int used) {
        var data = new SoftwareStudioWorkflowData("request");
        data.developmentModelTurnsUsed = used;
        data.developmentToolCallsUsed = used;
        var session = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT, data, null, files, Set.of());
        session.messages().add(ToolChatMessage.system("system"));
        session.messages().add(ToolChatMessage.user("Current goal"));
        return session;
    }
}
