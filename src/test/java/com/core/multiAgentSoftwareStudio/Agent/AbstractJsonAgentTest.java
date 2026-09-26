package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Service.Metric.LlmUsageMetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyList;
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
        when(model.generate(anyList()))
                .thenReturn(Response.from(AiMessage.from("not-json")))
                .thenReturn(Response.from(AiMessage.from("{\"value\":\"repaired\"}")));
        TestJsonAgent agent = new TestJsonAgent(
                model,
                new LangGraphPromptExecutor(metricsService),
                new ObjectMapper());

        Payload payload = agent.run();

        assertEquals("repaired", payload.value());
        assertEquals(1, metricsService.snapshot().outputValidationCounts().jsonParseError());
        org.mockito.ArgumentCaptor<java.util.List<ChatMessage>> messages =
                org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        org.mockito.Mockito.verify(model, org.mockito.Mockito.times(2)).generate(messages.capture());
        for (java.util.List<ChatMessage> call : messages.getAllValues()) {
            org.junit.jupiter.api.Assertions.assertInstanceOf(SystemMessage.class, call.get(0));
            org.junit.jupiter.api.Assertions.assertInstanceOf(UserMessage.class, call.get(1));
        }
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
