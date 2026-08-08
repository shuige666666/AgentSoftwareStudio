package com.core.multiAgentSoftwareStudio.Service.Metric;

import com.core.multiAgentSoftwareStudio.Model.Metric.LlmCallUsage;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmFailureType;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmOutputValidationType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmUsageMetricsServiceTest {

    @Test
    void formatsChineseTaskSummary() {
        LlmUsageMetricsService metricsService = new LlmUsageMetricsService();
        metricsService.beginTask();
        metricsService.recordSuccess(
                "deepseek-test",
                new LlmCallUsage(80, 20, 30, 130, true),
                1000,
                "stop");

        String summary = metricsService.formatSummary();

        assertTrue(summary.contains("[LLM用量汇总]"));
        assertTrue(summary.contains("输入Token(命中缓存)=80"));
        assertTrue(summary.contains("输入Token(未命中缓存)=20"));
        assertTrue(summary.contains("输出Token=30"));
        assertTrue(summary.contains("远程输入缓存命中率=80.00%"));
    }

    /**
     * 非 DeepSeek 供应商应保留通用 Token，但不展示伪造的零命中率。
     */
    @Test
    void formatsCacheMetricsAsUnavailableForGenericProvider() {
        LlmUsageMetricsService metricsService = new LlmUsageMetricsService();
        metricsService.beginTask();
        metricsService.recordSuccess(
                "qwen-test",
                new LlmCallUsage(0, 100, 30, 130, false),
                1000,
                "stop");

        String summary = metricsService.formatSummary();

        assertTrue(summary.contains("输入Token=100"));
        assertTrue(summary.contains("远程输入缓存命中率=N/A"));
        assertFalse(summary.contains("输入Token(命中缓存)"));
    }

    /**
     * 成功结束原因、调用失败和输出校验应独立聚合，避免重复计数。
     */
    @Test
    void aggregatesFinishReasonsFailuresAndOutputValidationSeparately() {
        LlmUsageMetricsService metricsService = new LlmUsageMetricsService();
        metricsService.beginTask();
        metricsService.recordSuccess(
                "qwen-test",
                new LlmCallUsage(0, 10, 20, 30, false),
                1000,
                "length");
        metricsService.recordFailure("qwen-test", 5000, LlmFailureType.TIMEOUT,
                new IllegalStateException("request timed out"));
        metricsService.recordOutputValidationFailure(LlmOutputValidationType.JSON_PARSE_ERROR);

        var snapshot = metricsService.snapshot();

        assertEquals(2, snapshot.calls());
        assertEquals(1, snapshot.successfulCalls());
        assertEquals(1, snapshot.failedCalls());
        assertEquals(1, snapshot.finishReasonCounts().length());
        assertEquals(snapshot.successfulCalls(), snapshot.finishReasonCounts().total());
        assertEquals(1, snapshot.failureCounts().timeout());
        assertEquals(snapshot.failedCalls(), snapshot.failureCounts().total());
        assertEquals(1, snapshot.outputValidationCounts().jsonParseError());
    }
}
