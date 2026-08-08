package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractJsonAgentTest {

    /**
     * 首次 JSON 解析失败即使修复成功，也应留下聚合计数供基准报告观测。
     */
    @Test
    void recordsJsonParseErrorBeforeSuccessfulRepair() {
        LlmUsageMetricsService metricsService = new LlmUsageMetricsService();
        metricsService.beginTask();
        ChatLanguageModel model = mock(ChatLanguageModel.class);
        when(model.generate(anyString()))
                .thenReturn("not-json")
                .thenReturn("{\"value\":\"repaired\"}");
        TestJsonAgent agent = new TestJsonAgent(
                model,
                new LangGraphPromptExecutor(metricsService),
                new ObjectMapper());

        Payload payload = agent.run();

        assertEquals("repaired", payload.value());
        assertEquals(1, metricsService.snapshot().outputValidationCounts().jsonParseError());
    }

    private static final class TestJsonAgent extends AbstractJsonAgent {
        private TestJsonAgent(ChatLanguageModel model,
                              LangGraphPromptExecutor promptExecutor,
                              ObjectMapper objectMapper) {
            super(model, promptExecutor, objectMapper);
        }

        private Payload run() {
            return askJson("Return JSON.", "Create one value.", Payload.class);
        }
    }

    private record Payload(String value) {
    }
}
