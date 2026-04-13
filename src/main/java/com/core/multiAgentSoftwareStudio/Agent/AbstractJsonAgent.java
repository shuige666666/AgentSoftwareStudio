package com.core.multiAgentSoftwareStudio.Agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * 给各个智能体复用的基础类
 */
public abstract class AbstractJsonAgent {

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
        String reply = askText(systemPrompt + "\nYou must output valid JSON only, without markdown fences.", userPrompt);
        String json = extractJson(reply);
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse model JSON response: " + json, e);
        }
    }

    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "{}";
        }
        int firstBrace = raw.indexOf('{');
        int lastBrace = raw.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return raw.substring(firstBrace, lastBrace + 1);
        }
        return raw.trim();
    }
}
