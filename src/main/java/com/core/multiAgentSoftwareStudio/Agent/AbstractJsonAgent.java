package com.core.multiAgentSoftwareStudio.Agent;

import com.core.multiAgentSoftwareStudio.Model.Metric.LlmOutputValidationType;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * 给各个智能体复用的基础类
 */
public abstract class AbstractJsonAgent {
    // 模型偶尔会返回不严格的 JSON；这里给一次“格式修复”机会，避免整个工作流直接中断。
    private static final int MAX_JSON_REPAIR_ATTEMPTS = 2;
    // 解析失败时只截取部分内容写入异常，防止整段源码把日志刷得过长。
    private static final int ERROR_SNIPPET_LIMIT = 2_000;

    protected final ChatLanguageModel model;
    protected final LangGraphPromptExecutor promptExecutor;
    protected final ObjectMapper objectMapper;

    protected AbstractJsonAgent(ChatLanguageModel model,
                                LangGraphPromptExecutor promptExecutor,
                                ObjectMapper objectMapper) {
        this.model = model;
        this.promptExecutor = promptExecutor;
        this.objectMapper = objectMapper;
    }

    protected String askText(String systemPrompt, String userPrompt) {
        String fullPrompt = "SYSTEM:\n" + systemPrompt + "\n\nUSER:\n" + userPrompt;
        return promptExecutor.execute(model, fullPrompt);
    }

    protected <T> T askJson(String systemPrompt, String userPrompt, Class<T> type) {
        String jsonOnlySystemPrompt = systemPrompt + "\nYou must output valid JSON only, without markdown fences.";
        String reply = askText(jsonOnlySystemPrompt, userPrompt);
        Exception lastParseError = null;

        for (int attempt = 0; attempt <= MAX_JSON_REPAIR_ATTEMPTS; attempt++) {
            String json = extractJson(reply);
            try {
                return objectMapper.readValue(json, type);
            } catch (Exception e) {
                promptExecutor.recordOutputValidationFailure(classifyOutputValidationFailure(e));
                lastParseError = e;
                if (attempt == MAX_JSON_REPAIR_ATTEMPTS) {
                    throw new IllegalStateException(
                            "Failed to parse model JSON response after repair attempts. Last JSON snippet: "
                                    + limitForError(json),
                            e);
                }

                // 只让模型修复 JSON 结构，不重新生成业务内容，尽量保留第一次回答里的代码和语义。
                reply = askText(buildJsonRepairSystemPrompt(type),
                        buildJsonRepairUserPrompt(reply, e));
            }
        }
        throw new IllegalStateException("Failed to parse model JSON response", lastParseError);
    }

    private String buildJsonRepairSystemPrompt(Class<?> type) {
        // 这个 prompt 把模型降级成“JSON 修复器”，专门处理字段未加引号、源码未转义等格式问题。
        return """
                You are a strict JSON repair tool.
                Convert the user's invalid model response into one valid JSON object matching the Java target type: %s.
                Preserve all semantic content and source code exactly.
                Do not add explanations, markdown fences, comments, or surrounding text.
                If source code appears inside a JSON string field, escape all newlines as \\n and all double quotes as \\".
                Return valid JSON only.
                """.formatted(type.getSimpleName());
    }

    private String buildJsonRepairUserPrompt(String invalidReply, Exception parseError) {
        return """
                The previous response could not be parsed as JSON.

                Parse error:
                %s

                Invalid response to repair:
                %s
                """.formatted(parseError.getMessage(), invalidReply == null ? "" : invalidReply);
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        // 允许模型偶尔在 JSON 前后夹说明文字；优先截取最外层大括号中的对象。
        int firstBrace = raw.indexOf('{');
        int lastBrace = raw.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return raw.substring(firstBrace, lastBrace + 1);
        }
        return raw.trim();
    }

    private String limitForError(String value) {
        if (value == null || value.length() <= ERROR_SNIPPET_LIMIT) {
            return value;
        }
        return value.substring(0, ERROR_SNIPPET_LIMIT) + "... [truncated]";
    }

    private LlmOutputValidationType classifyOutputValidationFailure(Exception error) {
        if (error instanceof JsonParseException) {
            return LlmOutputValidationType.JSON_PARSE_ERROR;
        }
        if (error instanceof JsonMappingException) {
            return LlmOutputValidationType.SCHEMA_VALIDATION_ERROR;
        }
        return LlmOutputValidationType.JSON_PARSE_ERROR;
    }
}
