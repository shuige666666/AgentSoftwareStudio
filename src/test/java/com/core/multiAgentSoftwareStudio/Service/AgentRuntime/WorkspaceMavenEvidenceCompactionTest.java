package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolCall;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 有完整失败诊断的旧Maven输出不能优先挤出当前可编辑源码。 */
class WorkspaceMavenEvidenceCompactionTest {
    @TempDir Path root;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 历史stdout可由日志入口追溯，保留错误、配对和当前准确正文。 */
    @Test
    void archivesDuplicateMavenOutputBeforeEvictingCurrentSource() throws Exception {
        var session = session();
        var failure = failure("mvn test 2>&1 | tail -80");
        String original = failure.toString();
        addRound(session, "test", "bash", original);
        String source = "current editable source\n".repeat(160);
        var read = mapper.createObjectNode();
        read.putArray("files").addObject().put("path", "src/App.java").put("sha256", "current-hash")
                .put("startLine", 1).put("endLine", 160).put("truncated", false).put("content", source);
        addRound(session, "read", "read_files", read.toString());
        addRound(session, "dto", "read_files", "{\"files\":[{\"path\":\"src/Dto.java\",\"content\":\"small DTO\"}]}");
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setMaxWorkingContextTokens(3_500);
        var compacted = new WorkspaceAgentContextCompactor(policy).compactForResume(session, 8);
        assertTrue(compacted.withinWorkingBudget());
        var retained = mapper.readTree(response(session, "read")).path("files").get(0);
        assertEquals(source, retained.path("content").asText());
        assertEquals("current-hash", retained.path("sha256").asText());
        var archived = mapper.readTree(response(session, "test"));
        assertFalse(archived.has("output"));
        assertTrue(archived.path("outputOmitted").asBoolean());
        for (String field : List.of("command", "passed", "exitCode", "failureHighlights", "testFailureEvidence", "fullLogPath", "changedFiles")) {
            assertEquals(failure.get(field), archived.get(field), field);
        }
        assertEquals(original, failure.toString());
        assertEquals(0, session.writeVersion());
        assertPairs(session);
    }

    /** 混合命令、写入、环境阻塞和诊断缺失继续保留原响应，避免隐藏未知副作用。 */
    @Test
    void preservesOutputsWithoutCompleteSourceFailureEvidence() {
        for (String kind : List.of("write", "mixed", "timeout", "infrastructure", "native-thread", "missing-command",
                "missing-log", "missing-evidence", "missing-writes", "passed", "cat")) {
            var session = session();
            var result = failure("mvn test");
            switch (kind) {
                case "write" -> result.withArray("changedFiles").add("src/App.java");
                case "mixed" -> result.put("command", "mvn test; cat src/App.java");
                case "timeout" -> result.put("timedOut", true);
                case "infrastructure" -> result.put("infrastructureError", "Docker unavailable");
                case "native-thread" -> result.put("output", "unable to create native thread");
                case "missing-command" -> result.put("exitCode", 127);
                case "missing-log" -> result.remove("fullLogPath");
                case "missing-evidence" -> { result.remove("failureHighlights"); result.remove("testFailureEvidence"); }
                case "missing-writes" -> result.remove("changedFiles");
                case "passed" -> { result.put("passed", true); result.put("exitCode", 0); }
                case "cat" -> result.put("command", "cat src/App.java");
            }
            String original = result.toString();
            addRound(session, "check", "bash", original);
            new WorkspaceAgentContextCompactor(new WorkspaceAgentLimitsConfig().getContext()).compactForResume(session, 8);
            assertEquals(original, response(session, "check"), kind);
            assertPairs(session);
        }
    }

    /** 最新诊断的固定摘要也应复用结构化失败证据，不能再次复制已省略的stdout。 */
    @Test
    void latestFailureSummaryDoesNotRepinDuplicateOutput() {
        var session = session();
        String original = failure("./mvnw -q test -Dfile.encoding=UTF-8").toString();
        addRound(session, "test", "bash", original);
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setMaxWorkingContextTokens(3_500);
        new WorkspaceAgentContextCompactor(policy).compact(session, "bash", original, 8);
        String input = session.messages().stream().map(ToolChatMessage::content).collect(java.util.stream.Collectors.joining("\n"));
        assertFalse(input.contains("duplicate-maven-stdout"));
        assertTrue(input.contains("Status expected:<200> but was:<404>"));
        assertTrue(input.contains("WidgetApiTest.results:42"));
        assertTrue(input.contains("maven-test.log"));
        assertPairs(session);
    }

    /** 构造正式Bash失败响应，stdout与独立诊断分别保存。 */
    private ObjectNode failure(String command) {
        var result = mapper.createObjectNode().put("command", command).put("passed", false).put("exitCode", 1)
                .put("timedOut", false).putNull("infrastructureError").put("cwd", root.toString())
                .put("output", "duplicate-maven-stdout\n".repeat(400))
                .put("failureHighlights", "Status expected:<200> but was:<404>")
                .put("testFailureEvidence", "WidgetApiTest.results:42")
                .put("fullLogPath", root.resolve("maven-test.log").toString());
        result.putArray("changedFiles");
        return result;
    }

    private WorkspaceAgentSession session() {
        var session = new WorkspaceAgentSession(root, WorkspaceAgentMode.DEVELOPMENT,
                new SoftwareStudioWorkflowData("generic application"), null, Map.of(), Set.of());
        session.messages().add(ToolChatMessage.system("system"));
        session.messages().add(ToolChatMessage.user("goal"));
        return session;
    }

    private void addRound(WorkspaceAgentSession session, String id, String tool, String result) {
        session.messages().add(ToolChatMessage.assistant("", List.of(new ToolCall(id, tool, "{}"))));
        session.messages().add(ToolChatMessage.tool(id, result));
    }

    private String response(WorkspaceAgentSession session, String id) {
        return session.messages().stream().filter(message -> "tool".equals(message.role()) && id.equals(message.toolCallId()))
                .findFirst().orElseThrow().content();
    }

    /** 压缩只能改写响应正文，工具请求与响应仍需完整配对。 */
    private void assertPairs(WorkspaceAgentSession session) {
        var requests = session.messages().stream().flatMap(message -> message.toolCalls().stream()).map(ToolCall::id).toList();
        var responses = session.messages().stream().filter(message -> "tool".equals(message.role())).map(ToolChatMessage::toolCallId).toList();
        assertEquals(requests, responses);
    }
}
