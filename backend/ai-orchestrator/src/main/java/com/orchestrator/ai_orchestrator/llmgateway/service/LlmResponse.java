package com.orchestrator.ai_orchestrator.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * What the model answered, already checked and unpacked.
 *
 * @param text          the text parts joined together (may be empty)
 * @param functionCalls tools the model wants to call (empty if it answered in text)
 * @param modelContent  the model's reply exactly as received. It must be sent back unchanged
 *                      in the next request, because it can carry data the API needs.
 * @param totalTokens   tokens used by this call (0 if the API did not report it)
 * @param finishReason  why the model stopped: STOP means it finished normally
 */
public record LlmResponse(
        String text,
        List<FunctionCall> functionCalls,
        JsonNode modelContent,
        int totalTokens,
        String finishReason) {

    /** A tool the model asked us to run. */
    public record FunctionCall(String name, JsonNode args) {}

    public boolean hasFunctionCalls() {
        return !functionCalls.isEmpty();
    }

    /** False when the answer was cut off (length limit) or stopped by a safety filter. */
    public boolean finishedNormally() {
        return "STOP".equals(finishReason);
    }
}