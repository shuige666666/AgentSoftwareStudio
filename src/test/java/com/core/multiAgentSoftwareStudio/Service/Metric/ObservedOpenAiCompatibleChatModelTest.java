package com.core.multiAgentSoftwareStudio.Service.Metric;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObservedOpenAiCompatibleChatModelTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 直连 DeepSeek 时应继续解析专用缓存字段。
     */
    @Test
    void parsesDeepSeekCacheUsageFieldsWhenEnabled() {
        ObservedOpenAiCompatibleChatModel model = newModel(true);
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
        assertTrue(parsed.cacheMetricsAvailable());
    }

    /**
     * 非 DeepSeek 配置只保留通用 Token，避免将不可统计误报为零命中。
     */
    @Test
    void treatsPromptTokensAsGenericInputWhenCacheMetricsAreDisabled() {
        ObservedOpenAiCompatibleChatModel model = newModel(false);
        ObjectNode usage = JsonNodeFactory.instance.objectNode();
        usage.put("prompt_tokens", 150);
        usage.put("prompt_cache_hit_tokens", 120);
        usage.put("prompt_cache_miss_tokens", 30);
        usage.put("completion_tokens", 25);
        usage.put("total_tokens", 175);

        var parsed = model.parseUsage(usage);

        assertEquals(0, parsed.inputCacheHitTokens());
        assertEquals(150, parsed.inputCacheMissTokens());
        assertEquals(150, parsed.inputTokens());
        assertEquals(25, parsed.outputTokens());
        assertEquals(175, parsed.totalTokens());
        assertFalse(parsed.cacheMetricsAvailable());
    }

    /**
     * 即使配置为 DeepSeek，响应未返回专用字段时也不应伪造缓存命中率。
     */
    @Test
    void marksCacheMetricsUnavailableWhenProviderFieldsAreAbsent() {
        ObservedOpenAiCompatibleChatModel model = newModel(true);
        ObjectNode usage = JsonNodeFactory.instance.objectNode();
        usage.put("prompt_tokens", 150);
        usage.put("completion_tokens", 25);
        usage.put("total_tokens", 175);

        var parsed = model.parseUsage(usage);

        assertEquals(150, parsed.inputTokens());
        assertFalse(parsed.cacheMetricsAvailable());
    }

    @Test
    void keepsPromptAsPlainUserMessage() {
        ObservedOpenAiCompatibleChatModel model = newModel(true);

        ObjectNode request = model.buildRequest(List.of(UserMessage.from("hello")));

        assertEquals("deepseek-test", request.path("model").asText());
        assertEquals("user", request.path("messages").path(0).path("role").asText());
        assertEquals("hello", request.path("messages").path(0).path("content").asText());
    }

    /**
     * JSON Agent 开启结构化输出时，请求体应显式要求 json_object。
     */
    @Test
    void enablesJsonObjectResponseFormatForJsonAgents() {
        ObservedOpenAiCompatibleChatModel model = newModel(true);

        ObjectNode request = model.buildRequest(List.of(UserMessage.from("return JSON")));

        assertEquals("json_object", request.path("response_format").path("type").asText());
    }

    /**
     * 保留可选开关，为未来不要求 JSON 的普通文本 Agent 避免误加结构化约束。
     */
    @Test
    void omitsJsonObjectResponseFormatWhenDisabled() {
        ObservedOpenAiCompatibleChatModel model = newModel(true, false);

        ObjectNode request = model.buildRequest(List.of(UserMessage.from("hello")));

        assertTrue(request.path("response_format").isMissingNode());
    }

    private ObservedOpenAiCompatibleChatModel newModel(boolean deepSeekCacheMetricsEnabled) {
        return newModel(deepSeekCacheMetricsEnabled, true);
    }

    private ObservedOpenAiCompatibleChatModel newModel(boolean deepSeekCacheMetricsEnabled,
                                                       boolean jsonOutputEnabled) {
        return new ObservedOpenAiCompatibleChatModel(
                "test-key",
                "https://api.deepseek.com",
                "deepseek-test",
                0.1,
                128,
                Duration.ofSeconds(5),
                objectMapper,
                new LlmUsageMetricsService(),
                deepSeekCacheMetricsEnabled,
                jsonOutputEnabled);
    }
}
