package com.orchestrator.ai_orchestrator.llmgateway.service;

/**
 * Thrown when the circuit breaker refuses a call. No request was sent to Gemini.
 * It is "retryable": trying again after the cooldown can succeed.
 */
public class CircuitOpenException extends LlmException {

    public CircuitOpenException(long secondsUntilRetry) {
        super("Gemini circuit breaker is open after repeated failures; "
                + "not calling Gemini for about " + secondsUntilRetry + " more second(s)", true);
    }
}