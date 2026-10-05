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

    /** 同源码续接后的摘要仍须包含独立业务诊断，不能只恢复原始工具消息。 */
    @Test void restoresProtectedFailureAlongWithPairedHistory() throws Exception {
        var first = session(Map.of("src/A.java", "class A {}"), 2);
        first.recordWrite("src/A.java");
        first.recordVerificationFailure("Compiler cannot find ItemService");
        first.recordBehaviorFeedback("Public behavior sourceHash=failed-hash: expected 404 but was 500");
        first.recordFinalFeedback("Final sourceHash=failed-hash: README.md is missing");
        first.messages().add(ToolChatMessage.assistant("Observed", List.of()));
        WorkspaceDevelopmentContext.save(first, first.baselineFiles());
        var resumed = session(first.baselineFiles(), 2);
        assertEquals(1, WorkspaceDevelopmentContext.restore(resumed));
        assertTrue(resumed.sourceVerificationFailure().contains("ItemService"));
        assertTrue(resumed.behaviorVerificationFailure().contains("expected 404"));
        assertTrue(resumed.finalVerificationFailure().contains("README.md"));
        assertEquals(1, resumed.writeVersion());
        resumed.recordTestsPassed();
        assertTrue(resumed.unresolvedVerificationFailure().contains("expected 404"));
        assertEquals(2, resumed.modelTurns());
    }

    /** v1 上下文没有独立诊断字段，兼容读取不能主动改写原文件。 */
    @Test void readsLegacyContextWithoutRewritingIt() throws Exception {
        var first = session(Map.of(), 2);
        first.messages().add(ToolChatMessage.assistant("Observed", List.of()));
        WorkspaceDevelopmentContext.save(first, first.baselineFiles());
        var file = root.resolve(".software-studio/development-context.json");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var json = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(file.toFile());
        json.put("schemaVersion", 1);
        json.remove("verificationFeedback");
        java.nio.file.Files.writeString(file, mapper.writeValueAsString(json), java.nio.charset.StandardCharsets.UTF_8);
        byte[] original = java.nio.file.Files.readAllBytes(file);
        var resumed = session(Map.of(), 2);
        assertEquals(1, WorkspaceDevelopmentContext.restore(resumed));
        assertTrue(resumed.unresolvedVerificationFailure().isBlank());
        assertArrayEquals(original, java.nio.file.Files.readAllBytes(file));
    }

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
