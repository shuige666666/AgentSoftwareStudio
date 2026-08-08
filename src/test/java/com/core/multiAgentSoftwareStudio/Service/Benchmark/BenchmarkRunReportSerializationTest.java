package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.core.multiAgentSoftwareStudio.Config.AiConfig;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.AgentModelAssignment;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkModelInfo;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkRunConfiguration;
import com.core.multiAgentSoftwareStudio.Model.Benchmark.BenchmarkRunReport;
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
                "unknown", "2026-08-05-update");
        AgentModelAssignment assignment = new AgentModelAssignment("TestWriterAgent", "coderModel");
        BenchmarkRunConfiguration config = new BenchmarkRunConfiguration(
                List.of("task-api-basic"), 3, "abc123");
        BenchmarkRunReport report = new BenchmarkRunReport(
                "2026-08-05T00:00:00Z", "2026-08-05T00:10:00Z",
                Map.of("coderModel", model), List.of(assignment), config,
                List.of(), 0, 0, 0, null, "benchmark-results/test.json");

        JsonNode json = new ObjectMapper().valueToTree(report);

        assertEquals("deepseek-v4-flash", json.at("/models/coderModel/requestedName").asText());
        assertTrue(json.at("/models/coderModel/reportedName").isNull());
        assertEquals("2026-08-05-update", json.at("/models/coderModel/manualReleaseLabel").asText());
        assertEquals("TestWriterAgent", json.at("/agentModelAssignments/0/agent").asText());
        assertEquals("coderModel", json.at("/agentModelAssignments/0/modelRef").asText());
        assertEquals(3, json.at("/benchmarkConfig/maxRetries").asInt());
        assertEquals("abc123", json.at("/benchmarkConfig/gitCommit").asText());
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
}
