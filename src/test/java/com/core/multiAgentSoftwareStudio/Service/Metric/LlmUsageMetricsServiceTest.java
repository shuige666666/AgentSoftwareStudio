package com.core.multiAgentSoftwareStudio.Service.Metric;

import com.core.multiAgentSoftwareStudio.Model.Metric.LlmCallUsage;
import org.junit.jupiter.api.Test;

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
                1000);

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
                1000);

        String summary = metricsService.formatSummary();

        assertTrue(summary.contains("输入Token=100"));
        assertTrue(summary.contains("远程输入缓存命中率=N/A"));
        assertFalse(summary.contains("输入Token(命中缓存)"));
    }
}
