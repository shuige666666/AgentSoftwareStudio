package com.core.multiAgentSoftwareStudio.Service.Metric;

import com.core.multiAgentSoftwareStudio.Model.Metric.LlmCallUsage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmUsageMetricsServiceTest {

    @Test
    void formatsChineseTaskSummary() {
        LlmUsageMetricsService metricsService = new LlmUsageMetricsService();
        metricsService.beginTask();
        metricsService.recordSuccess(
                "deepseek-test",
                new LlmCallUsage(80, 20, 30, 130),
                1000);

        String summary = metricsService.formatSummary();

        assertTrue(summary.contains("[LLM用量汇总]"));
        assertTrue(summary.contains("输入Token(命中缓存)=80"));
        assertTrue(summary.contains("输入Token(未命中缓存)=20"));
        assertTrue(summary.contains("输出Token=30"));
        assertTrue(summary.contains("远端输入缓存命中率=80.00%"));
    }
}
