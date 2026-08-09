package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Config.AiConfig;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.AgentModelAssignment;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkModelInfo;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkRunConfiguration;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkRunReport;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkVerificationSummary;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkCaseResult;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkQualityResult;
import com.core.multiAgentSoftwareStudio.Model.Workflow.FailureKind;
import com.core.multiAgentSoftwareStudio.Model.Workflow.WorkflowRunSummary;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmFailureCounts;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmFinishReasonCounts;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmOutputValidationCounts;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmUsageSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkRunReportSerializationTest {

    /**
     * 确保模型快照和可复现参数会随基准结果一起写入 JSON。
     */
    @Test
    void shouldSerializeModelAndRunMetadata() throws Exception {
        BenchmarkModelInfo model = new BenchmarkModelInfo(
                "deepseek-v4-flash", null, "api.deepseek.com",
                "https://api.deepseek.com", 0.1, 8192, 480,
                true, "unknown", "2026-08-05-update");
        AgentModelAssignment assignment = new AgentModelAssignment("TestWriterAgent", "coderModel");
        BenchmarkRunConfiguration config = new BenchmarkRunConfiguration(
                List.of("task-api-basic"), 3, "abc123", true, "sha256-local-tree");
        BenchmarkRunReport report = new BenchmarkRunReport(
                "2026-08-05T00:00:00Z", "2026-08-05T00:10:00Z",
                Map.of("coderModel", model), List.of(assignment), config,
                List.of(), 0, 0, 0, null, "benchmark-results/test.json");

        JsonNode json = new ObjectMapper().valueToTree(report);

        assertEquals("deepseek-v4-flash", json.at("/models/coderModel/requestedName").asText());
        assertTrue(json.at("/models/coderModel/reportedName").isNull());
        assertEquals("2026-08-05-update", json.at("/models/coderModel/manualReleaseLabel").asText());
        assertTrue(json.at("/models/coderModel/jsonOutputEnabled").asBoolean());
        assertEquals("TestWriterAgent", json.at("/agentModelAssignments/0/agent").asText());
        assertEquals("coderModel", json.at("/agentModelAssignments/0/modelRef").asText());
        assertEquals(3, json.at("/benchmarkConfig/maxRetries").asInt());
        assertEquals("abc123", json.at("/benchmarkConfig/gitCommit").asText());
        assertTrue(json.at("/benchmarkConfig/gitDirty").asBoolean());
        assertEquals("sha256-local-tree", json.at("/benchmarkConfig/workingTreeFingerprint").asText());
    }

    /**
     * 确保整批耗时会以分秒和精确毫秒数输出。
     */
    @Test
    void shouldFormatTotalElapsedForConsole() {
        assertEquals("35 分 7 秒（2107123 毫秒）", BenchmarkRunnerService.formatTotalElapsed(2_107_123));
    }

    /**
     * 确保 AiConfig 中所有 Agent 的模型 Qualifier 都能被报告自动发现。
     */
    @Test
    void shouldDiscoverEveryAgentModelAssignmentFromAiConfig() {
        List<AgentModelAssignment> assignments = BenchmarkRunnerService.agentModelAssignments();

        assertEquals(7, assignments.size());
        assertEquals(7, assignments.stream().map(AgentModelAssignment::agent).distinct().count());
        assertTrue(assignments.stream().allMatch(assignment ->
                assignment.modelRef().equals(AiConfig.CODER_MODEL_BEAN_NAME)
                        || assignment.modelRef().equals(AiConfig.LOGIC_MODEL_BEAN_NAME)));
    }

    /**
     * 基准 JSON 只序列化分类总量，不引入单次失败明细。
     */
    @Test
    void shouldSerializeAggregatedLlmOutcomeCounts() {
        LlmUsageSnapshot snapshot = new LlmUsageSnapshot(
                3, 2, 1, 1000, 0, 10, 20, 30, false,
                new LlmFinishReasonCounts(1, 1, 0, 0, 0),
                new LlmFailureCounts(1, 0, 0, 0, 0, 0),
                new LlmOutputValidationCounts(0, 1, 0));

        JsonNode json = new ObjectMapper().valueToTree(snapshot);

        assertEquals(1, json.at("/finishReasonCounts/length").asLong());
        assertEquals(1, json.at("/failureCounts/timeout").asLong());
        assertEquals(1, json.at("/outputValidationCounts/jsonParseError").asLong());
        assertTrue(json.path("failures").isMissingNode());
    }

    /**
     * 单用例报告应保留修复次数、最终失败分类和门禁告警数量，但不保存调用失败明细。
     */
    @Test
    void shouldSerializeWorkflowOutcomeSummary() {
        BenchmarkCaseResult result = new BenchmarkCaseResult(
                "snake-websocket-basic", false, false, "NOT_REVIEWED", "generated", 100,
                new BenchmarkQualityResult(false, List.of()),
                new LlmUsageSnapshot(1, 1, 0, 1, 0, 1, 1, 2, false),
                3, FailureKind.BUILD_PROFILE, 4,
                new BenchmarkVerificationSummary(
                        0, 1, true, false, 0, 0, 0, 120, 80, FailureKind.BUILD_PROFILE),
                new WorkflowRunSummary(
                        2, 3, 1, 0, 0, 0,
                        Map.of(FailureKind.BUILD_PROFILE, 1L),
                        Map.of(), Map.of("PROFILE_POM_XML", 1L)),
                null);

        JsonNode json = new ObjectMapper().valueToTree(result);

        assertEquals(3, json.at("/attemptsUsed").asInt());
        assertEquals("BUILD_PROFILE", json.at("/finalFailureKind").asText());
        assertEquals(4, json.at("/validationWarningCount").asInt());
        assertEquals(0, json.at("/verificationSummary/buildExitCode").asInt());
        assertEquals(1, json.at("/verificationSummary/testExitCode").asInt());
        assertEquals(1, json.at("/runSummary/repairAttempts").asInt());
        assertEquals(1, json.at("/runSummary/failedGateCounts/PROFILE_POM_XML").asInt());
    }
}
