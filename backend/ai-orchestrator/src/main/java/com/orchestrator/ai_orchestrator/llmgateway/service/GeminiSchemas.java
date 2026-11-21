package com.orchestrator.ai_orchestrator.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Converts a standard JSON Schema (as stored in tool_registry.input_schema) into the smaller
 * schema dialect Gemini accepts. Keeps only fields Gemini understands and writes type names
 * in upper case ("string" -> "STRING"), the form every Gemini API version accepts.
 */
public final class GeminiSchemas {

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    /** Fields that can be copied unchanged. */
    private static final List<String> COPIED_AS_IS =
            List.of("description", "format", "enum", "required", "minItems", "maxItems", "nullable");

    private GeminiSchemas() {
        // Static utility class.
    }

    public static JsonNode fromJsonSchema(JsonNode schema) {
        ObjectNode result = FACTORY.objectNode();
        if (schema == null || !schema.isObject()) {
            result.put("type", "OBJECT");
            return result;
        }

        JsonNode type = schema.get("type");
        if (type != null && type.isTextual()) {
            result.put("type", type.asText().toUpperCase(Locale.ROOT));
        }

        for (String key : COPIED_AS_IS) {
            if (schema.has(key)) {
                result.set(key, schema.get(key));
            }
        }

        JsonNode properties = schema.get("properties");
        if (properties != null && properties.isObject()) {
            ObjectNode converted = result.putObject("properties");
            Iterator<String> names = properties.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                converted.set(name, fromJsonSchema(properties.get(name)));
            }
        }

        JsonNode items = schema.get("items");
        if (items != null && items.isObject()) {
            result.set("items", fromJsonSchema(items));
        }
        return result;
    }
}