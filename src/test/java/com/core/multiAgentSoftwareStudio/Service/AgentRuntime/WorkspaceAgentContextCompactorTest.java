package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ApiEndpointContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.Contract.ProjectContract;
import com.core.multiAgentSoftwareStudio.Model.Generation.DeliverySlice;
import com.core.multiAgentSoftwareStudio.Model.Generation.SliceDeliveryPlan;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolCall;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestoneState;
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

        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setMaxContextTokens(100);
        WorkspaceAgentContextCompactor.Result result = new WorkspaceAgentContextCompactor(policy)
                .compact(session, "read_files", "result-7", -1);

        assertTrue(result.compacted());
        assertEquals("system", session.messages().get(0).content());
        assertEquals("original objective", session.messages().get(1).content());
        assertTrue(session.messages().get(2).content().contains("result-7"));
        assertFalse(session.messages().stream().anyMatch(message -> message.content().contains("assistant-4")));
        assertTrue(session.messages().stream().anyMatch(message -> message.content().contains("assistant-5")));
        assertEquals("call-7", session.messages().getLast().toolCallId());
    }

    /** 首任务通过后的交接与收尾提醒被裁掉时，压缩目标仍以当前持久化任务为准。 */
    @Test
    void refreshesCurrentMilestoneAfterHandoffAndCompaction() {
        var data = new SoftwareStudioWorkflowData("build a project");
        var first = new DevelopmentMilestoneState.Task("create", "create item", List.of("POST /items"),
                true, "accepted-hash", "snapshot", "report");
        var second = new DevelopmentMilestoneState.Task("edit", "edit item", List.of("PUT /items/{id}"),
                false, "", "", "");
        data.milestoneState = new DevelopmentMilestoneState(1, List.of(first, second), 0, 0, "");
        var editContract = new ProjectContract(List.of(new ApiEndpointContract("PUT", "/items/{id}",
                "", "", "ItemController", "")), List.of(), List.of(), List.of());
        data.sliceDeliveryPlan = new SliceDeliveryPlan(List.of(
                new DeliverySlice("create", "create item", List.of(), List.of(),
                        new ProjectContract(List.of(), List.of(), List.of(), List.of()), List.of(), List.of()),
                new DeliverySlice("edit", "edit item", List.of(), List.of(), editContract,
                        List.of("create"), List.of())), List.of(), "test");
        var session = new WorkspaceAgentSession(projectRoot, WorkspaceAgentMode.DEVELOPMENT,
                data, message -> { }, Map.of(), Set.of());
        session.messages().add(ToolChatMessage.system("system"));
        session.messages().add(ToolChatMessage.user("Current milestone: create"));
        for (int turn = 1; turn <= 10; turn++) {
            session.incrementModelTurns();
            session.messages().add(ToolChatMessage.assistant("assistant-" + turn,
                    List.of(new ToolCall("call-" + turn, "read_files", "{}"))));
            String result = turn == 5 ? "create accepted; next milestone edit: PUT /items/{id}"
                    : "result-" + turn;
            session.messages().add(ToolChatMessage.tool("call-" + turn, result));
            if (turn == 7) session.messages().add(ToolChatMessage.user("closing reminder for edit"));
        }
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setMaxContextTokens(100);

        var result = new WorkspaceAgentContextCompactor(policy)
                .compact(session, "read_files", "result-10", 5);

        assertTrue(result.compacted());
        String objective = session.messages().get(1).content();
        assertTrue(objective.contains("Current milestone: edit"));
        assertTrue(objective.contains("PUT /items/{id}"));
        assertTrue(objective.contains("create item [accepted at accepted-hash]"));
        assertFalse(objective.contains("Current milestone: create"));
        assertFalse(session.messages().stream().anyMatch(message -> message.content().contains("closing reminder")));
        assertTrue(session.messages().get(2).content().contains("remaining model turns: 5"));
        assertEquals("call-10", session.messages().getLast().toolCallId());
    }

    /** 连续开发的失败测试被后续读文件挤出历史时，压缩摘要仍保留待修错误。 */
    @Test
    void keepsUnresolvedTestFailureAfterLaterToolRounds() {
        var session = new WorkspaceAgentSession(projectRoot, WorkspaceAgentMode.DEVELOPMENT,
                new SoftwareStudioWorkflowData("build project"), message -> { }, Map.of(), Set.of());
        session.messages().add(ToolChatMessage.system("system"));
        session.messages().add(ToolChatMessage.user("complete project"));
        session.recordWrite("src/test/java/demo/AppTest.java");
        for (int turn = 1; turn <= 10; turn++) {
            session.messages().add(ToolChatMessage.assistant("assistant-" + turn,
                    List.of(new ToolCall("call-" + turn, "read_files", "{}"))));
            session.messages().add(ToolChatMessage.tool("call-" + turn, "result-" + turn));
            if (turn == 5) session.recordVerificationFailure(
                    "mvn test: AppTest.java:[57,35] method attribute cannot be applied");
        }
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setMaxContextTokens(100);

        var result = new WorkspaceAgentContextCompactor(policy)
                .compact(session, "read_files", "result-10", 5);

        assertTrue(result.compacted());
        assertTrue(session.messages().get(2).content().contains("AppTest.java:[57,35]"));
        assertTrue(session.messages().get(2).content().contains("write version 1"));
        assertFalse(session.messages().stream().anyMatch(message -> message.content().equals("result-5")));
        session.recordTestsPassed();
        assertEquals("", session.unresolvedVerificationFailure());
    }
}
