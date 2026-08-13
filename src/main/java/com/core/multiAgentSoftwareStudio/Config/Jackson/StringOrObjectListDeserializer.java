package com.core.multiAgentSoftwareStudio.Config.Jackson;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Accepts either ["story1", ...] or [{"story":"..."}, ...] from model JSON output.
 */
public class StringOrObjectListDeserializer extends JsonDeserializer<List<String>> {

    @Override
    public List<String> deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        JsonNode node = parser.getCodec().readTree(parser);
        List<String> result = new ArrayList<>();

        if (node == null || node.isNull()) {
            return result;
        }

        if (node.isArray()) {
            for (JsonNode item : node) {
                String value = toStoryText(item);
                if (value != null && !value.isBlank()) {
                    result.add(value);
                }
            }
            return result;
        }

        String singleValue = toStoryText(node);
        if (singleValue != null && !singleValue.isBlank()) {
            result.add(singleValue);
        }
        return result;
    }

    private String toStoryText(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isObject()) {
            String[] preferredFields = {"story", "title", "name", "description", "content"};
            for (String field : preferredFields) {
                JsonNode candidate = node.get(field);
                if (candidate != null && candidate.isTextual() && !candidate.asText().isBlank()) {
                    return candidate.asText();
                }
            }
            // Fallback: preserve object information as compact JSON text.
            return node.toString();
        }
        return node.asText(node.toString());
    }
}

