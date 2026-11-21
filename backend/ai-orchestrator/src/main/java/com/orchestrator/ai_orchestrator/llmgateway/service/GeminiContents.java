package com.orchestrator.ai_orchestrator.llmgateway.service;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** Builds the message objects Gemini expects, so no other class writes raw JSON. */
public final class GeminiContents {

    private static final JsonNodeFactory FACTORY = JsonNodeFactory.instance;

    private GeminiContents() {
        // Static factory methods only.
    }

    /** A user message containing plain text. */
    public static ObjectNode user(String text) {
        ObjectNode content = FACTORY.objectNode();
        content.put("role", "user");
        content.putArray("parts").addObject().put("text", text);
        return content;
    }

    /**
     * A user message carrying tool results back to the model. If the model asked for several
     * tools in one turn, ALL results go in this one message, one part per tool.
     */
    public static ObjectNode toolResults(List<ToolResult> results) {
        ObjectNode content = FACTORY.objectNode();
        content.put("role", "user");
        ArrayNode parts = content.putArray("parts");
        for (ToolResult result : results) {
            ObjectNode functionResponse = parts.addObject().putObject("functionResponse");
            functionResponse.put("name", result.name());
            functionResponse.putObject("response").put("result", result.result());
        }
        return content;
    }

    /** The outcome of running one tool. */
    public record ToolResult(String name, String result) {}
}