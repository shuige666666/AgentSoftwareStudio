package com.core.multiAgentSoftwareStudio.Service.Metric;

import com.core.multiAgentSoftwareStudio.Model.Metric.LlmCallUsage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.output.TokenUsage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible Chat 模型包装器，统一记录通用 Token 用量并可选观测 DeepSeek 缓存指标。
 */
public class ObservedOpenAiCompatibleChatModel implements ChatLanguageModel {
    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    private final double temperature;
    private final int maxTokens;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final LlmUsageMetricsService metricsService;
    private final boolean deepSeekCacheMetricsEnabled;
    private final HttpClient httpClient;

    public ObservedOpenAiCompatibleChatModel(String apiKey,
                                             String baseUrl,
                                             String modelName,
                                             double temperature,
                                             int maxTokens,
                                             Duration timeout,
                                             ObjectMapper objectMapper,
                                             LlmUsageMetricsService metricsService,
                                             boolean deepSeekCacheMetricsEnabled) {
        this.apiKey = apiKey;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.modelName = modelName;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
        this.deepSeekCacheMetricsEnabled = deepSeekCacheMetricsEnabled;
        this.httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Response<AiMessage> generate(List<ChatMessage> messages) {
        long started = System.nanoTime();
        try {
            ObjectNode requestBody = buildRequest(messages == null ? List.of() : messages);
            String rawResponse = postChatCompletion(requestBody);
            JsonNode root = objectMapper.readTree(rawResponse);
            if (root.has("error")) {
                throw new IllegalStateException("OpenAI-compatible API returned error: " + root.get("error"));
            }

            String content = root.path("choices").path(0).path("message").path("content").asText();
            LlmCallUsage usage = parseUsage(root.path("usage"));
            metricsService.recordSuccess(modelName, usage, elapsedMillis(started));

            return Response.from(
                    AiMessage.from(content),
                    new TokenUsage(
                            safeInt(usage.inputTokens()),
                            safeInt(usage.outputTokens()),
                            safeInt(usage.totalTokens())),
                    mapFinishReason(root.path("choices").path(0).path("finish_reason").asText()),
                    usageMetadata(usage));
        } catch (IOException e) {
            metricsService.recordFailure(modelName, elapsedMillis(started), e);
            throw new IllegalStateException("Failed to call OpenAI-compatible API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metricsService.recordFailure(modelName, elapsedMillis(started), e);
            throw new IllegalStateException("Interrupted while calling OpenAI-compatible API", e);
        } catch (RuntimeException e) {
            metricsService.recordFailure(modelName, elapsedMillis(started), e);
            throw e;
        }
    }

    public String modelName() {
        return modelName;
    }

    @Override
    public String toString() {
        return getClass().getName() + ":" + modelName;
    }

    ObjectNode buildRequest(List<ChatMessage> messages) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.put("temperature", temperature);
        root.put("max_tokens", maxTokens);

        ArrayNode messageNodes = root.putArray("messages");
        if (messages.isEmpty()) {
            addMessage(messageNodes, "user", "");
            return root;
        }
        for (ChatMessage message : messages) {
            addMessage(messageNodes, roleOf(message), message.text());
        }
        return root;
    }

    /**
     * 通用 Token 始终按 OpenAI 字段读取；DeepSeek 缓存字段只在配置启用且响应真实返回时生效。
     */
    LlmCallUsage parseUsage(JsonNode usage) {
        boolean cacheMetricsAvailable = deepSeekCacheMetricsEnabled
                && (usage.has("prompt_cache_hit_tokens") || usage.has("prompt_cache_miss_tokens"));
        long hitTokens = cacheMetricsAvailable
                ? usage.path("prompt_cache_hit_tokens").asLong(0)
                : 0;
        long promptTokens = usage.path("prompt_tokens").asLong(0);
        long missTokens = cacheMetricsAvailable
                ? usage.path("prompt_cache_miss_tokens").asLong(Math.max(0, promptTokens - hitTokens))
                : promptTokens;

        if (promptTokens == 0 && cacheMetricsAvailable) {
            promptTokens = hitTokens + missTokens;
        }

        long outputTokens = usage.path("completion_tokens").asLong(0);
        long totalTokens = usage.path("total_tokens").asLong(promptTokens + outputTokens);
        return new LlmCallUsage(hitTokens, missTokens, outputTokens, totalTokens, cacheMetricsAvailable);
    }

    private Map<String, Object> usageMetadata(LlmCallUsage usage) {
        if (!usage.cacheMetricsAvailable()) {
            return Map.of("cache_metrics_available", false);
        }
        return Map.of(
                "cache_metrics_available", true,
                "prompt_cache_hit_tokens", usage.inputCacheHitTokens(),
                "prompt_cache_miss_tokens", usage.inputCacheMissTokens());
    }

    private void addMessage(ArrayNode messages, String role, String content) {
        ObjectNode message = messages.addObject();
        message.put("role", role);
        message.put("content", content == null ? "" : content);
    }

    private String roleOf(ChatMessage message) {
        if (message == null || message.type() == null) {
            return "user";
        }
        ChatMessageType type = message.type();
        if (type == ChatMessageType.SYSTEM) {
            return "system";
        }
        if (type == ChatMessageType.AI) {
            return "assistant";
        }
        if (type == ChatMessageType.TOOL_EXECUTION_RESULT) {
            return "tool";
        }
        return "user";
    }

    private String postChatCompletion(ObjectNode requestBody) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/chat/completions"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("OpenAI-compatible API HTTP " + response.statusCode() + ": " + response.body());
        }
        return response.body();
    }

    private FinishReason mapFinishReason(String finishReason) {
        return switch (finishReason == null ? "" : finishReason) {
            case "stop" -> FinishReason.STOP;
            case "length" -> FinishReason.LENGTH;
            case "tool_calls", "function_call" -> FinishReason.TOOL_EXECUTION;
            case "content_filter" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.OTHER;
        };
    }

    private int safeInt(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) Math.max(0, value);
    }

    private long elapsedMillis(long startedNano) {
        return (System.nanoTime() - startedNano) / 1_000_000L;
    }

    private String normalizeBaseUrl(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("OpenAI-compatible baseUrl must not be blank");
        }
        String trimmed = value.strip();
        if (trimmed.endsWith("/chat/completions")) {
            return trimmed.substring(0, trimmed.length() - "/chat/completions".length());
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
