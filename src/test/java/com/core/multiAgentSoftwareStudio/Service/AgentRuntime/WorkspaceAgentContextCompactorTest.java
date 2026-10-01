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
    /** 单任务压缩同样保留初始失败，收到新验证后用当前失败替代旧中断证据。 */
    @Test
    void singleTaskCompactionReplacesSavedFailureWithCurrentEvidence() {
        var session = contextSession();
        session.workflowData().milestoneState = new DevelopmentMilestoneState(2, List.of(
                new DevelopmentMilestoneState.Task("single", "single", List.of(), false, "", "", "")),
                36, 36, "old-missing-bean-evidence");
        addRound(session, "inspect", "list_files", "{}", "{\"files\":[\"App.java\"]}");
        var compactor = new WorkspaceAgentContextCompactor(new WorkspaceAgentLimitsConfig().getContext());
        compactor.compactForRetry(session, "list_files", "{\"files\":[\"App.java\"]}", 10);
        assertTrue(session.messages().get(1).content().contains("old-missing-bean-evidence"));
        session.recordCheckpoint("new-source-hash", false, false);
        session.recordBehaviorFeedback("current-status-mismatch-evidence");
        compactor.compactForRetry(session, "list_files", "{\"files\":[\"App.java\"]}", 9);
        assertFalse(session.messages().get(1).content().contains("old-missing-bean-evidence"));
        assertTrue(session.messages().get(2).content().contains("current-status-mismatch-evidence"));
        assertTrue(session.messages().get(1).content().contains("submit_checkpoint(scope=final)"));
        assertPairs(session.messages());
    }

    /** 重复读取只保留最新准确正文；旧哈希仍明确属于历史，全部工具 ID 保持配对。 */
    @Test
    void boundsRepeatedFileReadsAndPreservesNewestText() throws Exception {
        var session = contextSession();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (int turn = 1; turn <= 3; turn++) {
            var result = mapper.createObjectNode();
            result.putArray("files").addObject().put("path", "App.java").put("sha256", "version-" + turn)
                    .put("content", ("exact-version-" + turn + "\r\n").repeat(550));
            addRound(session, "call-" + turn, "read_files", "{}", result.toString());
        }
        session.recordWrite("App.java");
        session.recordVerificationFailure("AppTest: expected 404 but was 200");
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setMaxWorkingContextTokens(7_000);

        var result = new WorkspaceAgentContextCompactor(policy).compact(session, "read_files",
                session.messages().getLast().content(), 5);

        assertTrue(result.compacted());
        assertTrue(result.withinWorkingBudget());
        assertEquals(3, session.messages().stream().filter(m -> "assistant".equals(m.role())).count());
        var old = mapper.readTree(session.messages().get(4).content()).at("/files/0");
        assertFalse(old.has("content"));
        assertTrue(old.path("contentOmitted").asBoolean());
        assertEquals("version-1", old.path("sha256").asText());
        var newest = mapper.readTree(session.messages().getLast().content()).at("/files/0");
        assertEquals(("exact-version-3\r\n").repeat(550), newest.path("content").asText());
        assertTrue(session.messages().get(2).content().contains("expected 404 but was 200"));
        assertPairs(session.messages());
    }

    /** 最近一轮有多个大读取时，移出正文也不拆开工具响应，磁盘文件不被改写。 */
    @Test
    void dropsOversizedBodiesWithoutProvidingTruncatedEditableCode() throws Exception {
        var session = contextSession();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        session.messages().add(ToolChatMessage.assistant("inspect", List.of(
                new ToolCall("read-a", "read_files", "{}"), new ToolCall("read-b", "read_files", "{}"))));
        for (String id : List.of("read-a", "read-b")) {
            var result = mapper.createObjectNode();
            result.putArray("files").addObject().put("path", id + ".java").put("sha256", id)
                    .put("content", "exact-source-body".repeat(3_000));
            session.messages().add(ToolChatMessage.tool(id, result.toString()));
        }
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setMaxWorkingContextTokens(2_000);

        var result = new WorkspaceAgentContextCompactor(policy).compact(session, "read_files",
                session.messages().getLast().content(), 12);

        assertTrue(result.withinWorkingBudget());
        assertPairs(session.messages());
        assertFalse(session.messages().stream().anyMatch(m -> m.content().contains("exact-source-body")));
        assertTrue(session.messages().stream().anyMatch(m -> m.content().contains("contentOmitted")));
        assertEquals("original objective", session.messages().get(1).content());
    }

    /** 大补丁整体移出历史，不修改已完成调用的参数或制造孤立 tool 消息。 */
    @Test
    void evictsAnOversizedPatchAsAWholeTurn() {
        var session = contextSession();
        addRound(session, "patch", "apply_patch", "complete-patch".repeat(6_000),
                "{\"changedFiles\":[\"App.java\"],\"ok\":true}");
        session.recordWrite("App.java");
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setMaxWorkingContextTokens(2_000);

        var result = new WorkspaceAgentContextCompactor(policy).compact(session, "apply_patch",
                session.messages().getLast().content(), 4);

        assertTrue(result.withinWorkingBudget());
        assertEquals(3, session.messages().size());
        assertTrue(session.messages().getLast().content().contains("App.java"));
        assertTrue(session.messages().getLast().content().contains("write version: 1"));
        assertPairs(session.messages());
    }

    /** 预算不足以容纳目标时明确报告，不以删需求或删未解决错误制造达标。 */
    @Test
    void preservesMandatoryFactsEvenWhenPinnedContextExceedsBudget() {
        var session = contextSession();
        session.recordVerificationFailure("critical-public-contract-failure");
        addRound(session, "call", "read_files", "{}", "{}");
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setMaxWorkingContextTokens(10);

        var result = new WorkspaceAgentContextCompactor(policy).compact(session, "read_files", "{}", 2);

        assertFalse(result.withinWorkingBudget());
        assertEquals("original objective", session.messages().get(1).content());
        assertTrue(session.messages().get(2).content().contains("critical-public-contract-failure"));
        assertTrue(session.messages().get(2).content().contains("remaining model turns: 2"));
    }

    /** 首次请求仍在工作预算内时，超时重试也会整理读取正文并保留原调用配对。 */
    @Test
    void reducesRedundantReadsForRetryBelowTheNormalThreshold() throws Exception {
        var session = contextSession();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var response = mapper.createObjectNode();
        response.putArray("files").addObject().put("path", "App.java").put("sha256", "current-hash")
                .put("content", "large-exact-body".repeat(1_000));
        addRound(session, "read", "read_files", "{}", response.toString());
        var compactor = new WorkspaceAgentContextCompactor(new WorkspaceAgentLimitsConfig().getContext());
        assertFalse(compactor.compact(session, "read_files", response.toString(), 10).compacted());

        var result = compactor.compactForRetry(session, "read_files", response.toString(), 9);

        assertTrue(result.compacted());
        assertEquals("timeout_retry", result.reason());
        assertTrue(result.estimatedTokensAfter() < result.estimatedTokensBefore());
        assertTrue(session.messages().getLast().content().contains("current-hash"));
        assertPairs(session.messages());
    }

    /** 文件在读取后已修改，未重新读取的旧正文必须失效，但工具证据配对仍完整。 */
    @Test
    void invalidatesReadBodiesAfterALaterWorkspaceWrite() throws Exception {
        var session = contextSession();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var read = mapper.createObjectNode();
        read.putArray("files").addObject().put("path", "App.java").put("sha256", "old")
                .put("content", "old-accurate-source");
        addRound(session, "read", "read_files", "{}", read.toString());
        addRound(session, "patch", "apply_patch", "{}",
                "{\"ok\":true,\"applied\":[\"App.java\"],\"failedPath\":\"\",\"error\":\"\"}");
        session.recordWrite("App.java");
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setCompactAtPercent(1);
        policy.setMaxContextTokens(2_000);

        new WorkspaceAgentContextCompactor(policy).compact(session, "apply_patch",
                session.messages().getLast().content(), 6);

        assertFalse(session.messages().stream().anyMatch(m -> m.content().contains("old-accurate-source")));
        var historical = mapper.readTree(session.messages().get(4).content()).at("/files/0");
        assertTrue(historical.path("contentOmitted").asBoolean());
        assertEquals("old", historical.path("sha256").asText());
        assertPairs(session.messages());
    }

    /** 压缩后仍保留完整目标和建议式策略，不能重新注入首任务顺序限制。 */
    @Test
    void advisoryPolicySurvivesContextCompaction() {
        var session = contextSession();
        var data = session.workflowData();
        data.userRequest = "complete public requirements";
        data.milestoneState = new com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestoneState(2,
                List.of(new com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestoneState.Task(
                        "a", "a", List.of(), false, "", "", ""),
                        new com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestoneState.Task(
                        "b", "b", List.of(), false, "", "", "")), 0, 0, "",
                com.core.multiAgentSoftwareStudio.Model.Workflow.DevelopmentMilestonePolicy.ADVISORY, "");
        addRound(session, "read", "read_files", "{}", "{}");
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setCompactAtPercent(1);
        policy.setMaxContextTokens(100);
        var result = new WorkspaceAgentContextCompactor(policy).compact(session, "read_files", "{}", 4);
        assertTrue(result.compacted());
        String goal = session.messages().get(1).content();
        assertTrue(goal.contains("complete public requirements"));
        assertTrue(goal.contains("combine tasks"));
        assertTrue(goal.contains("scope=final"));
        assertFalse(goal.contains("Defer later endpoints"));
        assertPairs(session.messages());
    }

    private WorkspaceAgentSession contextSession() {
        var session = new WorkspaceAgentSession(projectRoot, WorkspaceAgentMode.DEVELOPMENT,
                new SoftwareStudioWorkflowData("test"), message -> { }, Map.of(), Set.of());
        session.messages().add(ToolChatMessage.system("system"));
        session.messages().add(ToolChatMessage.user("original objective"));
        return session;
    }

    private void addRound(WorkspaceAgentSession session, String id, String name, String arguments, String result) {
        session.messages().add(ToolChatMessage.assistant("", List.of(new ToolCall(id, name, arguments))));
        session.messages().add(ToolChatMessage.tool(id, result));
    }

    /** 每个保留的 assistant 调用恰有一个响应，且响应在任何下一条 user/assistant 前完成。 */
    private void assertPairs(List<ToolChatMessage> messages) {
        var pending = new java.util.HashSet<String>();
        for (var message : messages) {
            if ("assistant".equals(message.role())) {
                assertTrue(pending.isEmpty());
                message.toolCalls().forEach(call -> assertTrue(pending.add(call.id())));
            } else if ("tool".equals(message.role())) {
                assertTrue(pending.remove(message.toolCallId()));
            } else {
                assertTrue(pending.isEmpty());
            }
        }
        assertTrue(pending.isEmpty());
    }

    @TempDir
    Path projectRoot;

    @Test
    void keepsOlderCompleteToolRoundsWhenTheyStillFitBudget() {
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
        policy.setMaxContextTokens(2_000);
        policy.setCompactAtPercent(1);
        WorkspaceAgentContextCompactor.Result result = new WorkspaceAgentContextCompactor(policy)
                .compact(session, "read_files", "result-7", -1);

        assertTrue(result.compacted());
        assertEquals("system", session.messages().get(0).content());
        assertEquals("original objective", session.messages().get(1).content());
        assertTrue(session.messages().get(2).content().contains("result-7"));
        assertTrue(session.messages().stream().anyMatch(message -> message.content().contains("assistant-4")));
        assertTrue(session.messages().stream().anyMatch(message -> message.content().contains("assistant-5")));
        assertEquals("call-7", session.messages().getLast().toolCallId());
        assertTrue(result.withinWorkingBudget());
        assertPairs(session.messages());
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
        policy.setMaxContextTokens(2_000);
        policy.setCompactAtPercent(1);

        var result = new WorkspaceAgentContextCompactor(policy)
                .compact(session, "read_files", "result-10", 5);

        assertTrue(result.compacted());
        String objective = session.messages().get(1).content();
        assertTrue(objective.contains("Current milestone: edit"));
        assertTrue(objective.contains("PUT /items/{id}"));
        assertTrue(objective.contains("create item [accepted at accepted-hash]"));
        assertFalse(objective.contains("Current milestone: create"));
        assertTrue(session.messages().stream().anyMatch(message -> message.content().contains("closing reminder")));
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
            session.messages().add(ToolChatMessage.tool("call-" + turn, "result-" + turn + " diagnostic".repeat(70)));
            if (turn == 5) session.recordVerificationFailure(
                    "mvn test: AppTest.java:[57,35] method attribute cannot be applied");
        }
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setMaxContextTokens(2_000);
        policy.setCompactAtPercent(1);

        var result = new WorkspaceAgentContextCompactor(policy)
                .compact(session, "read_files", "result-10", 5);

        assertTrue(result.compacted());
        assertTrue(session.messages().get(2).content().contains("AppTest.java:[57,35]"));
        assertTrue(session.messages().get(2).content().contains("write version 1"));
        assertFalse(session.messages().stream().anyMatch(message -> message.content().startsWith("result-5")));
        session.recordTestsPassed();
        assertEquals("", session.unresolvedVerificationFailure());
    }

    /** 同版本的相邻片段均可用于编辑；不同的行范围不能按文件名去重。 */
    @Test
    void retainsDisjointRangesOfTheSameFile() {
        var session = contextSession();
        addRound(session, "first", "read_files", "{}", range(1, 10, "same", "first-range"));
        addRound(session, "second", "read_files", "{}", range(11, 20, "same", "second-range"));
        var result = forceCompaction(session);
        assertTrue(result.compacted());
        assertTrue(session.messages().stream().anyMatch(message -> message.content().contains("first-range")));
        assertTrue(session.messages().stream().anyMatch(message -> message.content().contains("second-range")));
        assertPairs(session.messages());
    }

    /** 连续片段可覆盖旧范围，但新版本即使只读一部分也使旧版本正文失效。 */
    @Test
    void removesCoveredRangesAndInvalidatesOlderHashes() {
        var session = contextSession();
        addRound(session, "whole", "read_files", "{}", range(1, 20, "same", "old-whole"));
        addRound(session, "first", "read_files", "{}", range(1, 10, "same", "first-range"));
        addRound(session, "second", "read_files", "{}", range(11, 20, "same", "second-range"));
        forceCompaction(session);
        assertFalse(session.messages().stream().anyMatch(message -> message.content().contains("old-whole")));
        addRound(session, "changed", "read_files", "{}", range(5, 6, "new-hash", "new-version"));
        forceCompaction(session);
        assertFalse(session.messages().stream().anyMatch(message -> message.content().contains("first-range")));
        assertFalse(session.messages().stream().anyMatch(message -> message.content().contains("second-range")));
        assertTrue(session.messages().stream().anyMatch(message -> message.content().contains("new-version")));
        assertPairs(session.messages());
    }

    /** 一个超大读取不能把同轮的小文件一起丢掉，正文移出后仍保留配对和文件元信息。 */
    @Test
    void evictsLargeFileBodyWithoutDroppingSmallFileInSameResponse() {
        var session = contextSession();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var result = mapper.createObjectNode();
        var files = result.putArray("files");
        files.addObject().put("path", "LargeTest.java").put("sha256", "large")
                .put("content", "large-body".repeat(3_000));
        files.addObject().put("path", "Small.java").put("sha256", "small").put("content", "small-exact-source");
        addRound(session, "read", "read_files", "{}", result.toString());
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setMaxWorkingContextTokens(2_000);
        var compacted = new WorkspaceAgentContextCompactor(policy).compact(session, "read_files", result.toString(), 6);
        assertTrue(compacted.withinWorkingBudget());
        assertTrue(session.messages().stream().anyMatch(message -> message.content().contains("small-exact-source")));
        assertFalse(session.messages().stream().anyMatch(message -> message.content().contains("large-body")));
        assertPairs(session.messages());
    }

    /** 旧片段与新片段合计超预算时，先回收旧片段，保证刚读取的正文有一次被模型使用的机会。 */
    @Test
    void retainsFreshLargerRangeBeforeOlderSmallerRangeUnderPressure() throws Exception {
        var session = contextSession();
        String older = "a".repeat(3_000);
        String fresh = "b".repeat(5_000);
        addRound(session, "older", "read_files", "{}", range(1, 160, "same", older));
        addRound(session, "fresh", "read_files", "{}", range(161, 410, "same", fresh));
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setMaxWorkingContextTokens(3_700);

        var result = new WorkspaceAgentContextCompactor(policy).compact(session, "read_files",
                session.messages().getLast().content(), 12);

        assertTrue(result.withinWorkingBudget());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var newest = mapper.readTree(session.messages().getLast().content()).at("/files/0");
        assertEquals(fresh, newest.path("content").asText());
        assertEquals(161, newest.path("startLine").asInt());
        assertEquals("same", newest.path("sha256").asText());
        assertFalse(session.messages().stream().anyMatch(message -> message.content().contains(older)));
        assertPairs(session.messages());
    }

    /** 当前 checkpoint 已提供新诊断时，不再将历史失败重复注入任务目标。 */
    @Test
    void replacesHistoricGoalFailureAfterACurrentCheckpoint() {
        var session = contextSession();
        var data = session.workflowData();
        data.sliceDeliveryPlan = SliceDeliveryPlan.empty();
        data.milestoneState = new DevelopmentMilestoneState(2, List.of(new DevelopmentMilestoneState.Task(
                "current-task", "current-goal", List.of("current-criterion"), false, "", "", ""),
                new DevelopmentMilestoneState.Task("later-task", "later-goal", List.of(), false, "", "", "")),
                0, 0, "historic-failure");
        session.recordCheckpoint("current-hash", false, false);
        session.recordVerificationFailure("current-source-failure");
        addRound(session, "read", "read_files", "{}", "{\"files\":[]}");
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setMaxContextTokens(2_000);
        policy.setCompactAtPercent(1);

        new WorkspaceAgentContextCompactor(policy).compact(session, "read_files", "{\"files\":[]}", 5);

        assertFalse(session.messages().get(1).content().contains("historic-failure"));
        assertTrue(session.messages().get(1).content().contains("current-goal"));
        assertTrue(session.messages().get(2).content().contains("current-source-failure"));
        assertPairs(session.messages());
    }

    /** 两类长诊断分别保留首尾，防止合并截断后丢掉独立业务的第一项失败。 */
    @Test
    void preservesBothFailureChannelsWhenOneLongReportWouldHideTheOther() {
        var session = contextSession();
        session.recordVerificationFailure("first source failure " + "s".repeat(8_000) + " last source failure");
        session.recordBehaviorFeedback("first public failure request=POST /items expected=400 "
                + "b".repeat(8_000) + " last public failure sourceHash=bound");
        addRound(session, "read", "read_files", "{}", range(1, 10, "hash", "evidence".repeat(100)));
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setMaxContextTokens(16_000);
        policy.setCompactAtPercent(1);

        var result = new WorkspaceAgentContextCompactor(policy).compact(session, "read_files",
                session.messages().getLast().content(), 4);

        String summary = session.messages().get(2).content();
        assertTrue(result.withinWorkingBudget());
        assertTrue(summary.contains("first source failure"));
        assertTrue(summary.contains("last source failure"));
        assertTrue(summary.contains("first public failure request=POST /items expected=400"));
        assertTrue(summary.contains("last public failure sourceHash=bound"));
        assertPairs(session.messages());
    }

    /** 独立业务失败跨越后续读取和普通自测仍留在压缩摘要中。 */
    @Test
    void preservesIndependentBehaviorFailureAfterSelfTestsPass() {
        var session = contextSession();
        session.recordBehaviorFeedback("GET /missing expected 404 but was 200 sourceHash=verified");
        session.recordTestsPassed();
        for (int index = 0; index < 5; index++) addRound(session, "read-" + index, "read_files", "{}", "{}");
        forceCompaction(session);
        assertTrue(session.messages().get(2).content().contains("expected 404 but was 200"));
        session.recordBehaviorFeedback("");
        forceCompaction(session);
        assertFalse(session.messages().get(2).content().contains("expected 404 but was 200"));
    }

    private WorkspaceAgentContextCompactor.Result forceCompaction(WorkspaceAgentSession session) {
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setCompactAtPercent(1);
        policy.setMaxContextTokens(2_000);
        return new WorkspaceAgentContextCompactor(policy).compact(session, "read_files", session.messages().getLast().content(), 6);
    }

    /** 验证大报告已登记失败后应优先移出，当前源码与真实检查版本仍可见。 */
    @Test
    void archivesOversizedCheckpointWithoutEvictingUsefulSource() throws Exception {
        var session = contextSession();
        session.recordVerificationFailure("AppTest expected 404 but was 200");
        session.recordCheckpoint("verified-hash", false, true);
        addRound(session, "source", "read_files", "{}", range(1, 10, "source", "accurate-controller"));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String report = mapper.createObjectNode().put("scope", "final").put("status", "FAILED")
                .put("sourceHash", "verified-hash").put("reportPath", "checkpoint.json")
                .put("failureOutput", "long-diagnostic".repeat(3_000)).toString();
        addRound(session, "verify", "submit_checkpoint", "{}", report);
        session.messages().add(ToolChatMessage.user("Platform validation of current candidate; " + report));
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setMaxWorkingContextTokens(2_000);
        policy.setMaxLatestToolResultChars(1_000);
        var compacted = new WorkspaceAgentContextCompactor(policy).compact(session, "submit_checkpoint", report, 5);
        assertTrue(compacted.withinWorkingBudget());
        assertTrue(session.messages().stream().anyMatch(message -> message.content().contains("accurate-controller")));
        assertTrue(session.messages().get(2).content().contains("AppTest expected 404 but was 200"));
        assertTrue(session.messages().get(2).content().contains("verified-hash"));
        assertFalse(session.messages().stream().anyMatch(message -> message.content().contains("long-diagnostic")));
        assertFalse(session.completed());
        assertPairs(session.messages());
    }

    /** 环境阻塞未登记成源码失败，历史精简不能隐藏其具体原因。 */
    @Test
    void keepsBlockedCheckpointDiagnosis() {
        var session = contextSession();
        String report = "{\"scope\":\"progress\",\"status\":\"BLOCKED\",\"sourceHash\":\"hash\","
                + "\"failureOutput\":\"Docker preview failed to start\"}";
        addRound(session, "verify", "submit_checkpoint", "{}", report);
        var policy = new WorkspaceAgentLimitsConfig().getContext();
        policy.setCompactAtPercent(1);
        policy.setMaxContextTokens(2_000);
        new WorkspaceAgentContextCompactor(policy).compact(session, "submit_checkpoint", report, 6);
        assertTrue(session.messages().get(2).content().contains("Docker preview failed to start"));
        assertPairs(session.messages());
    }

    private String range(int start, int end, String hash, String body) {
        return "{\"files\":[{\"path\":\"App.java\",\"sha256\":\"" + hash + "\",\"startLine\":" + start
                + ",\"endLine\":" + end + ",\"partial\":true,\"content\":\"" + body + "\"}]}";
    }
}
