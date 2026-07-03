package com.core.multiAgentSoftwareStudio.Service.Metric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservedDeepSeekChatModelTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void parsesDeepSeekCacheUsageFields() {
        ObservedDeepSeekChatModel model = newModel();
        ObjectNode usage = JsonNodeFactory.instance.objectNode();
        usage.put("prompt_cache_hit_tokens", 120);
        usage.put("prompt_cache_miss_tokens", 30);
        usage.put("completion_tokens", 25);
        usage.put("total_tokens", 175);

        var parsed = model.parseUsage(usage);

        assertEquals(120, parsed.inputCacheHitTokens());
        assertEquals(30, parsed.inputCacheMissTokens());
        assertEquals(25, parsed.outputTokens());
        assertEquals(175, parsed.totalTokens());
    }

    @Test
    void treatsPromptTokensAsMissWhenCacheFieldsAreAbsent() {
        ObservedDeepSeekChatModel model = newModel();
        ObjectNode usage = JsonNodeFactory.instance.objectNode();
        usage.put("prompt_tokens", 150);
        usage.put("completion_tokens", 25);
        usage.put("total_tokens", 175);

        var parsed = model.parseUsage(usage);

        assertEquals(0, parsed.inputCacheHitTokens());
        assertEquals(150, parsed.inputCacheMissTokens());
        assertEquals(25, parsed.outputTokens());
        assertEquals(175, parsed.totalTokens());
    }

    @Test
    void keepsPromptAsPlainUserMessage() {
        ObservedDeepSeekChatModel model = newModel();

        ObjectNode request = model.buildRequest(List.of(UserMessage.from("hello")));

        assertEquals("deepseek-test", request.path("model").asText());
        assertEquals("user", request.path("messages").path(0).path("role").asText());
        assertEquals("hello", request.path("messages").path(0).path("content").asText());
    }

    private ObservedDeepSeekChatModel newModel() {
        return new ObservedDeepSeekChatModel(
                "test-key",
                "https://api.deepseek.com",
                "deepseek-test",
                0.1,
                128,
                Duration.ofSeconds(5),
                objectMapper,
                new LlmUsageMetricsService());
    }
}
