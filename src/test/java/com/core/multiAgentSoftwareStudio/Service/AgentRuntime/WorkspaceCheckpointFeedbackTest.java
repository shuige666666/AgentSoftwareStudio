package com.core.multiAgentSoftwareStudio.Service.AgentRuntime;

import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorFinding;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkBehaviorResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceCheckpointFeedbackTest {
    /** 没有 HTTP 判卷条目时，保存续接状态仍须保留当前源码的启动失败原因。 */
    @Test
    void finalFailureSummaryRetainsStartupReasonWithoutFindings() {
        var behavior = BenchmarkBehaviorResult.notRun("required a bean of type 'demo.Service' that could not be found; full log=target/startup.log");
        var checkpoint = new WorkspaceCheckpointService.CheckpointResult("final", "FAILED", "startup-hash", "snapshot", "report.json",
                com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStepResult.notRun(
                        com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStage.BUILD),
                com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStepResult.notRun(
                        com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStage.TEST),
                false, List.of(), "test wrapper\nPublic behavior checks (sourceHash=startup-hash): raw-json", false, false, behavior);
        String summary = WorkspaceCheckpointFeedback.failureSummary(checkpoint);
        assertTrue(summary.contains("status=NOT_RUN"));
        assertTrue(summary.contains("required a bean of type 'demo.Service'"));
        assertTrue(summary.contains("full log=target/startup.log"));
        assertFalse(summary.contains("raw-json"));
        assertTrue(WorkspaceCheckpointFeedback.behaviorFailures(BenchmarkBehaviorResult.environmentError("Docker unavailable"),
                "env-hash", "report.json").contains("reason=Docker unavailable"));
    }

    /** 保存状态时保留底层原因和失败请求，去掉重复的完整业务正文并控制体积。 */
    @Test
    void finalFailureSummaryRetainsActionableEvidenceWithoutDuplicatingRawBehavior() {
        var behavior = new BenchmarkBehaviorResult("FAIL", List.of(
                new BenchmarkBehaviorFinding("reject", false, "HTTP 500; Location=; body=" + "large-html".repeat(500),
                        "FAIL", "POST /items; invalid form", "400; reject invalid input")), null);
        String errors = "Observed cause: NoSuchBeanDefinitionException: demo.Store\n" + "wrapper".repeat(900)
                + "\nPublic behavior checks (sourceHash=saved-hash): raw-business-json";
        var checkpoint = new WorkspaceCheckpointService.CheckpointResult("final", "FAILED", "saved-hash", "snapshot", "report.json",
                com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStepResult.notRun(
                        com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStage.BUILD),
                com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStepResult.notRun(
                        com.core.multiAgentSoftwareStudio.Model.Workflow.VerificationStage.TEST),
                false, List.of(), errors, false, false, behavior);
        String summary = WorkspaceCheckpointFeedback.failureSummary(checkpoint);
        assertTrue(summary.contains("NoSuchBeanDefinitionException: demo.Store"));
        assertTrue(summary.contains("sourceHash=saved-hash"));
        assertTrue(summary.contains("request=POST /items; invalid form expected=400"));
        assertTrue(summary.contains("actual=HTTP 500"));
        assertFalse(summary.contains("raw-business-json"));
        assertFalse(summary.contains("large-html"));
        assertTrue(summary.length() < 8_500);
    }

    /** 状态码已不匹配时省去 HTML，使一组失败请求同时进入业务诊断预算。 */
    @Test
    void keepsAllStatusFailuresWithinTheBehaviorSummaryBudget() {
        var findings = java.util.stream.IntStream.range(0, 9).mapToObj(index ->
                new BenchmarkBehaviorFinding("failure-" + index, false,
                        "HTTP 200; Location=; body=" + "page-style-html".repeat(40), "FAIL",
                        "POST /items/" + index + "; form=name=invalid", "400; invalid request must be rejected")).toList();
        String feedback = WorkspaceCheckpointFeedback.behaviorFailures(
                new BenchmarkBehaviorResult("FAIL", findings, null), "a".repeat(64), "report-path/".repeat(15));
        assertTrue(feedback.length() < 3_000);
        for (int index = 0; index < 9; index++) assertTrue(feedback.contains("failure-" + index + " [FAIL]"));
        assertTrue(feedback.contains("actual=HTTP 200; Location="));
        assertFalse(feedback.contains("page-style-html"));
        assertTrue(findings.getFirst().evidence().contains("page-style-html"));
    }

    /** 成功页面不进入修复摘要，失败和阻塞仍保留真实请求、预期、实际及源码版本。 */
    @Test
    void preservesFailuresAndBlockedRequestsWithoutPassingResponseBodies() {
        var success = new BenchmarkBehaviorFinding("success", true, "passing-html".repeat(100), "PASS", "GET /ok", "200");
        var failure = new BenchmarkBehaviorFinding("reject", false, "HTTP 200; failure-body".repeat(100), "FAIL",
                "POST /items; invalid form", "400");
        var blocked = new BenchmarkBehaviorFinding("dependent", false, "prerequisite missing", "BLOCKED",
                "GET /items/1", "200");
        var result = new BenchmarkBehaviorResult("FAIL", List.of(success, failure, blocked), null);

        String feedback = WorkspaceCheckpointFeedback.behaviorFailures(result, "frozen-hash", "checkpoint.json");

        assertTrue(feedback.contains("sourceHash=frozen-hash"));
        assertTrue(feedback.contains("passed=1 unresolved=2 report=checkpoint.json"));
        assertTrue(feedback.contains("request=POST /items; invalid form expected=400 actual=HTTP 200"));
        assertTrue(feedback.contains("dependent [BLOCKED] request=GET /items/1"));
        assertTrue(feedback.contains("response evidence omitted"));
        assertFalse(feedback.contains("passing-html"));
        assertTrue(feedback.length() < 700);
        assertEquals(3, result.findings().size());
        assertEquals("passing-html".repeat(100), result.findings().getFirst().evidence());
    }
}
