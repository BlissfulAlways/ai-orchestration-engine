package com.orchestrator.ai_orchestrator.llmgateway.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

/**
 * One request to the model.
 *
 * @param jobId                the job that pays for this call (stored in llm_call_logs)
 * @param callingComponent     who is asking, e.g. "TASK_PLANNER" (stored in llm_call_logs)
 * @param systemPrompt         standing instructions; sent in Gemini's dedicated field
 * @param contents             the conversation so far, in Gemini's message format
 * @param functionDeclarations tools the model may call, or null for none
 * @param responseSchema       if set, the model must answer with JSON matching this schema
 * @param temperature          0 = predictable, higher = more varied
 */
public record LlmRequest(
        UUID jobId,
        String callingComponent,
        String systemPrompt,
        List<JsonNode> contents,
        JsonNode functionDeclarations,
        JsonNode responseSchema,
        double temperature) {

    private static final double DEFAULT_TEMPERATURE = 0.3;

    /** A plain one-shot text request: instructions plus one user message. */
    public static LlmRequest text(UUID jobId, String callingComponent,
                                  String systemPrompt, String userPrompt) {
        return new LlmRequest(jobId, callingComponent, systemPrompt,
                List.of(GeminiContents.user(userPrompt)), null, null, DEFAULT_TEMPERATURE);
    }
}