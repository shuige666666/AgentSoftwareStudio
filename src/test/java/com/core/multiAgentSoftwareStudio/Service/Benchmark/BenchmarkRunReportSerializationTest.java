package com.core.multiAgentSoftwareStudio.Service.Benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BenchmarkRunReportSerializationTest {

    /**
     * 确保模型快照和可复现参数会随基准结果一起写入 JSON。
     */
    @Test
    void shouldSerializeModelAndRunMetadata() throws Exception {
        BenchmarkModelInfo model = new BenchmarkModelInfo(
                "coderModel", "deepseek-v4-flash", null, "api.deepseek.com",
                "https://api.deepseek.com", 0.1, 8192, 480,
                "unknown", "2026-08-05-update");
        BenchmarkRunConfiguration config = new BenchmarkRunConfiguration(
                List.of("task-api-basic"), 3, "abc123");
        BenchmarkRunReport report = new BenchmarkRunReport(
                "2026-08-05T00:00:00Z", "2026-08-05T00:10:00Z",
                List.of(model), config, List.of(), 0, 0, 0, null, "benchmark-results/test.json");

        JsonNode json = new ObjectMapper().valueToTree(report);

        assertEquals("deepseek-v4-flash", json.at("/models/0/requestedName").asText());
        assertTrue(json.at("/models/0/reportedName").isNull());
        assertEquals("2026-08-05-update", json.at("/models/0/manualReleaseLabel").asText());
        assertEquals(3, json.at("/benchmarkConfig/maxRetries").asInt());
        assertEquals("abc123", json.at("/benchmarkConfig/gitCommit").asText());
    }
}
