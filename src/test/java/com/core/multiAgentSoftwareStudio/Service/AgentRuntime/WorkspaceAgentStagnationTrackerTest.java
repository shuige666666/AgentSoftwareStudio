package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Config.WorkspaceAgentLimitsConfig;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolCall;
import com.core.multiAgentSoftwareStudio.Service.Workflow.SoftwareStudioWorkflowData;
import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** 相同准备方法失败的日志位置和耗时变化不能掩盖重复根因；开发先获得诊断机会。 */
    @Test
    void bashFailureAdvisesBeforeStoppingDespiteLogNoise() {
        var tracker = tracker();
        var session = developmentSession();
        var call = new ToolCall("verify", "bash", "{\"command\":\"cd /workspace && mvn test | tail -100\"}");
        session.recordWrite("src/test/java/AppTest.java");
        assertFalse(tracker.observe(call, bashFailure("AppTest.setUp:22 Status expected:<404> but was:<200>", 100), session).stop());
        session.recordWrite("src/test/java/AppTest.java");
        var advice = tracker.observe(call, bashFailure("AppTest.setUp:24 Status expected:<404> but was:<200>", 999), session);
        assertFalse(advice.stop());
        assertTrue(advice.guidance().contains("failing test setup"));
        session.recordWrite("src/test/java/AppTest.java");
        assertTrue(tracker.observe(call, bashFailure("AppTest.setUp:26 Status expected:<404> but was:<200>", 123), session).stop());
    }

    /** 编译和定向测试成功都不能清除尚未解决的全量测试失败。 */
    @Test
    void compileAndFocusedTestPassDoNotResetFullTestFailure() {
        var tracker = tracker();
        var session = developmentSession();
        var full = new ToolCall("full", "bash", "{\"command\":\"mvn test\"}");
        session.recordWrite("src/main/java/App.java");
        tracker.observe(full, bashFailure("AppTest.setup:22 Status expected:<404> but was:<200>", 10), session);
        tracker.observe(new ToolCall("compile", "bash", "{\"command\":\"mvn compile\"}"),
                "{\"passed\":true,\"output\":\"BUILD SUCCESS\"}", session);
        tracker.observe(new ToolCall("focused", "bash", "{\"command\":\"mvn -Dtest=OtherTest test\"}"),
                "{\"passed\":true,\"output\":\"Tests run: 1, Failures: 0, Errors: 0\"}", session);
        session.recordWrite("src/main/java/App.java");
        assertFalse(tracker.observe(full, bashFailure("AppTest.setup:22 Status expected:<404> but was:<200>", 20), session)
                .guidance().isBlank());
    }

    /** 已修复部分错误或出现下一层错误属于新进展，不按同一失败提前终止。 */
    @Test
    void improvedFailureSummaryResetsRepeatedFailure() {
        var tracker = tracker();
        var session = developmentSession();
        var call = new ToolCall("test", "bash", "{\"command\":\"mvn test\"}");
        session.recordWrite("App.java");
        tracker.observe(call, bashFailure("Tests run: 14, Failures: 14, Errors: 0", 10), session);
        session.recordWrite("App.java");
        var improved = tracker.observe(call, bashFailure("Tests run: 14, Failures: 2, Errors: 0", 20), session);
        assertFalse(improved.stop());
        assertTrue(improved.guidance().isBlank());
    }

    /** 正式 checkpoint 使用结构化状态；新的源码哈希和报告路径不改变同一失败。 */
    @Test
    void checkpointFailureParticipatesWithoutCountingMetadataAsProgress() {
        var tracker = tracker();
        var session = developmentSession();
        var call = new ToolCall("checkpoint", "submit_checkpoint", "{\"scope\":\"progress\"}");
        session.recordWrite("App.java");
        tracker.observe(call, checkpointFailure("first"), session);
        session.recordWrite("App.java");
        assertFalse(tracker.observe(call, checkpointFailure("second"), session).guidance().isBlank());
    }

    /** 环境阻塞和普通诊断不应累积成源码修复停滞。 */
    @Test
    void environmentAndNonMavenCommandsDoNotTriggerRepairAdvice() {
        var tracker = tracker();
        var session = developmentSession();
        for (int index = 0; index < 3; index++) {
            session.recordWrite("App.java");
            var blocked = tracker.observe(new ToolCall("checkpoint", "submit_checkpoint", "{}"),
                    "{\"status\":\"BLOCKED\",\"failureOutput\":\"unable to create native thread\"}", session);
            assertFalse(blocked.stop());
            assertTrue(blocked.guidance().isBlank());
            var threadFailure = tracker.observe(new ToolCall("checkpoint", "submit_checkpoint", "{}"),
                    "{\"status\":\"FAILED\",\"failureOutput\":\"unable to create native thread\"}", session);
            assertFalse(threadFailure.stop());
            assertTrue(threadFailure.guidance().isBlank());
            var missingTool = tracker.observe(new ToolCall("missing", "bash", "{\"command\":\"./mvnw compile\"}"),
                    "{\"passed\":false,\"exitCode\":127,\"output\":\"bash: ./mvnw: No such file or directory\"}", session);
            assertFalse(missingTool.stop());
            assertTrue(missingTool.guidance().isBlank());
            var diagnostic = tracker.observe(new ToolCall("read", "bash", "{\"command\":\"cat pom.xml\"}"),
                    "{\"passed\":false,\"output\":\"File not found\"}", session);
            assertFalse(diagnostic.stop());
            assertTrue(diagnostic.guidance().isBlank());
        }
    }

    /** 全套测试真实通过后，同样的后续失败应视为一次新回归。 */
    @Test
    void greenFullTestClearsPreviousFailure() {
        var tracker = tracker();
        var session = developmentSession();
        var call = new ToolCall("test", "bash", "{\"command\":\"mvn test -DskipTests=false\"}");
        session.recordWrite("App.java");
        tracker.observe(call, bashFailure("AppTest.setup:22 failure", 10), session);
        tracker.observe(call, "{\"passed\":true,\"output\":\"Tests run: 2, Failures: 0, Errors: 0\"}", session);
        session.recordWrite("App.java");
        var regression = tracker.observe(call, bashFailure("AppTest.setup:22 failure", 20), session);
        assertFalse(regression.stop());
        assertTrue(regression.guidance().isBlank());
    }

    private WorkspaceAgentStagnationTracker tracker() {
        return new WorkspaceAgentStagnationTracker(new WorkspaceAgentLimitsConfig().getStagnation());
    }

    private WorkspaceAgentSession developmentSession() {
        return new WorkspaceAgentSession(projectRoot, WorkspaceAgentMode.DEVELOPMENT,
                new SoftwareStudioWorkflowData("test"), message -> { }, Map.of(), Set.of());
    }

    private String bashFailure(String failure, int duration) {
        return new ObjectMapper().createObjectNode().put("passed", false).put("exitCode", 1)
                .put("durationMillis", duration).put("fullLogPath", "E:/logs/" + duration + ".log")
                .put("output", "[ERROR] " + failure).toString();
    }

    private String checkpointFailure(String hash) {
        var node = new ObjectMapper().createObjectNode().put("status", "FAILED").put("sourceHash", hash)
                .put("reportPath", "E:/reports/" + hash).put("failureOutput", "[ERROR] AppTest.setup:22 failure");
        node.putObject("build").put("status", "PASSED");
        node.putObject("test").put("status", "FAILED");
        return node.toString();
    }

    private WorkspaceAgentSession session() {
        return new WorkspaceAgentSession(projectRoot, WorkspaceAgentMode.REPAIR_IMPLEMENTATION,
                new SoftwareStudioWorkflowData("test"), message -> { }, Map.of(), Set.of());
    }
}
