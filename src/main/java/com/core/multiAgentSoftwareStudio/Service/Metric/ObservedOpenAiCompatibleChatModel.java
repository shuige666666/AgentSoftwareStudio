package com.core.multiAgentSoftwareStudio.Service.Metric;

import com.core.multiAgentSoftwareStudio.Model.Metric.LlmCallUsage;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmFailureType;
import com.core.multiAgentSoftwareStudio.Model.Metric.LlmOutputValidationType;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolCall;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolChatMessage;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolDefinition;
import com.core.multiAgentSoftwareStudio.Model.Tool.ToolModelResponse;
import com.core.multiAgentSoftwareStudio.Service.AgentRuntime.ToolCallingModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.JsonProcessingException;
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
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI-compatible Chat 模型包装器，统一记录通用 Token 用量并可选观测 DeepSeek 缓存指标。
 */
public class ObservedOpenAiCompatibleChatModel implements ChatLanguageModel, ToolCallingModel {
    private final String apiKey;
    private final String baseUrl;
    private final String modelName;
    private final double temperature;
    private final int maxTokens;
    private final Duration timeout;
    private final ObjectMapper objectMapper;
    private final LlmUsageMetricsService metricsService;
    private final boolean deepSeekCacheMetricsEnabled;
    private final boolean jsonOutputEnabled;
    private final HttpClient httpClient;

    public ObservedOpenAiCompatibleChatModel(String apiKey,
                                             String baseUrl,
                                             String modelName,
                                             double temperature,
                                             int maxTokens,
                                             Duration timeout,
                                             ObjectMapper objectMapper,
                                             LlmUsageMetricsService metricsService,
                                             boolean deepSeekCacheMetricsEnabled,
                                             boolean jsonOutputEnabled) {
        this.apiKey = apiKey;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.modelName = modelName;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeout = timeout;
        this.objectMapper = objectMapper;
        this.metricsService = metricsService;
        this.deepSeekCacheMetricsEnabled = deepSeekCacheMetricsEnabled;
        this.jsonOutputEnabled = jsonOutputEnabled;
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
                throw new ProviderResponseException("OpenAI-compatible API returned error: " + root.get("error"));
            }

            String content = root.path("choices").path(0).path("message").path("content").asText();
            String finishReason = root.path("choices").path(0).path("finish_reason").asText();
            LlmCallUsage usage = parseUsage(root.path("usage"));
            metricsService.recordSuccess(modelName, usage, elapsedMillis(started), finishReason);
            if (content.isBlank()) {
                metricsService.recordOutputValidationFailure(LlmOutputValidationType.EMPTY_CONTENT);
            }

            return Response.from(
                    AiMessage.from(content),
                    new TokenUsage(
                            safeInt(usage.inputTokens()),
                            safeInt(usage.outputTokens()),
                            safeInt(usage.totalTokens())),
                    mapFinishReason(finishReason),
                    usageMetadata(usage));
        } catch (HttpTimeoutException e) {
            metricsService.recordFailure(modelName, elapsedMillis(started), LlmFailureType.TIMEOUT, e);
            throw new IllegalStateException("OpenAI-compatible API request timed out", e);
        } catch (JsonProcessingException e) {
            metricsService.recordFailure(modelName, elapsedMillis(started), LlmFailureType.RESPONSE_PARSE_ERROR, e);
            throw new IllegalStateException("Failed to parse OpenAI-compatible API response", e);
        } catch (IOException e) {
            metricsService.recordFailure(modelName, elapsedMillis(started), LlmFailureType.HTTP_ERROR, e);
            throw new IllegalStateException("Failed to call OpenAI-compatible API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metricsService.recordFailure(modelName, elapsedMillis(started), LlmFailureType.INTERRUPTED, e);
            throw new IllegalStateException("Interrupted while calling OpenAI-compatible API", e);
        } catch (ModelHttpException e) {
            metricsService.recordFailure(modelName, elapsedMillis(started), LlmFailureType.HTTP_ERROR, e);
            throw e;
        } catch (ProviderResponseException e) {
            metricsService.recordFailure(modelName, elapsedMillis(started), LlmFailureType.PROVIDER_ERROR, e);
            throw e;
        } catch (RuntimeException e) {
            metricsService.recordFailure(modelName, elapsedMillis(started), LlmFailureType.UNKNOWN, e);
            throw e;
        }
    }

    /**
     * 使用 OpenAI 兼容的 tools/tool_calls 协议执行一次工具会话模型调用。
     */
    @Override
    public ToolModelResponse generateWithTools(
            List<ToolChatMessage> messages,
            List<ToolDefinition> tools) {
        long started = System.nanoTime();
        try {
            ObjectNode requestBody = buildToolRequest(messages, tools);
            String rawResponse = postChatCompletion(requestBody);
            JsonNode root = objectMapper.readTree(rawResponse);
            if (root.has("error")) {
                throw new ProviderResponseException("OpenAI-compatible API returned error: " + root.get("error"));
            }

            JsonNode messageNode = root.path("choices").path(0).path("message");
            String content = messageNode.path("content").isNull() ? "" : messageNode.path("content").asText("");
            List<ToolCall> calls = parseToolCalls(messageNode.path("tool_calls"));
            String finishReason = root.path("choices").path(0).path("finish_reason").asText();
            LlmCallUsage usage = parseUsage(root.path("usage"));
            metricsService.recordSuccess(modelName, usage, elapsedMillis(started), finishReason);
            if (content.isBlank() && calls.isEmpty()) {
                metricsService.recordOutputValidationFailure(LlmOutputValidationType.EMPTY_CONTENT);
            }
            return new ToolModelResponse(ToolChatMessage.assistant(content, calls), finishReason);
        } catch (HttpTimeoutException e) {
            metricsService.recordFailure(modelName, elapsedMillis(started), LlmFailureType.TIMEOUT, e);
            throw new IllegalStateException("OpenAI-compatible tool request timed out", e);
        } catch (JsonProcessingException e) {
            metricsService.recordFailure(modelName, elapsedMillis(started), LlmFailureType.RESPONSE_PARSE_ERROR, e);
            throw new IllegalStateException("Failed to parse OpenAI-compatible tool response", e);
        } catch (IOException e) {
            metricsService.recordFailure(modelName, elapsedMillis(started), LlmFailureType.HTTP_ERROR, e);
            throw new IllegalStateException("Failed to call OpenAI-compatible tool API", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            metricsService.recordFailure(modelName, elapsedMillis(started), LlmFailureType.INTERRUPTED, e);
            throw new IllegalStateException("Interrupted while calling tool API", e);
        } catch (ModelHttpException e) {
            metricsService.recordFailure(modelName, elapsedMillis(started), LlmFailureType.HTTP_ERROR, e);
            throw e;
        } catch (ProviderResponseException e) {
            metricsService.recordFailure(modelName, elapsedMillis(started), LlmFailureType.PROVIDER_ERROR, e);
            throw e;
        } catch (RuntimeException e) {
            metricsService.recordFailure(modelName, elapsedMillis(started), LlmFailureType.UNKNOWN, e);
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
        if (jsonOutputEnabled) {
            root.putObject("response_format").put("type", "json_object");
        }

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
     * 构造工具请求；工具会话不能附带全局 JSON Mode，否则部分兼容服务会拒绝 tool_calls。
     */
    ObjectNode buildToolRequest(List<ToolChatMessage> messages, List<ToolDefinition> tools) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", modelName);
        root.put("temperature", temperature);
        root.put("max_tokens", maxTokens);
        root.put("tool_choice", "auto");
        root.put("parallel_tool_calls", false);

        ArrayNode messageNodes = root.putArray("messages");
        for (ToolChatMessage message : messages == null ? List.<ToolChatMessage>of() : messages) {
            ObjectNode node = messageNodes.addObject();
            node.put("role", message.role());
            if ("tool".equals(message.role())) {
                node.put("tool_call_id", message.toolCallId());
                node.put("content", message.content());
                continue;
            }
            if (message.content() == null || message.content().isBlank()) {
                node.putNull("content");
            } else {
                node.put("content", message.content());
            }
            if (message.toolCalls() != null && !message.toolCalls().isEmpty()) {
                ArrayNode calls = node.putArray("tool_calls");
                for (ToolCall call : message.toolCalls()) {
                    ObjectNode callNode = calls.addObject();
                    callNode.put("id", call.id());
                    callNode.put("type", "function");
                    ObjectNode function = callNode.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.arguments());
                }
            }
        }

        ArrayNode toolNodes = root.putArray("tools");
        for (ToolDefinition tool : tools == null ? List.<ToolDefinition>of() : tools) {
            ObjectNode toolNode = toolNodes.addObject();
            toolNode.put("type", "function");
            ObjectNode function = toolNode.putObject("function");
            function.put("name", tool.name());
            function.put("description", tool.description());
            function.set("parameters", tool.parameters());
        }
        return root;
    }

    private List<ToolCall> parseToolCalls(JsonNode callsNode) {
        if (callsNode == null || !callsNode.isArray()) {
            return List.of();
        }
        java.util.ArrayList<ToolCall> calls = new java.util.ArrayList<>();
        for (JsonNode call : callsNode) {
            JsonNode function = call.path("function");
            calls.add(new ToolCall(
                    call.path("id").asText(),
                    function.path("name").asText(),
                    function.path("arguments").asText("{}")));
        }
        return List.copyOf(calls);
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
            throw new ModelHttpException(
                    "OpenAI-compatible API HTTP " + response.statusCode() + ": " + response.body());
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

    private static final class ModelHttpException extends IllegalStateException {
        private ModelHttpException(String message) {
            super(message);
        }
    }

    private static final class ProviderResponseException extends IllegalStateException {
        private ProviderResponseException(String message) {
            super(message);
        }
    }
}
