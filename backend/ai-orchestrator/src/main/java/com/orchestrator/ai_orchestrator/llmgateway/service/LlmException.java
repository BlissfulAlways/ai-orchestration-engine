package com.orchestrator.ai_orchestrator.llmgateway.service;

/**
 * Thrown when a model call fails for good. {@link #isRetryable()} says whether trying again
 * later could help (a rate limit, a server error) or not (a blocked prompt, a bad API key).
 */
public class LlmException extends RuntimeException {

    private final boolean retryable;

    public LlmException(String message, boolean retryable) {
        super(message);
        this.retryable = retryable;
    }

    public LlmException(String message, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
    }

    public boolean isRetryable() {
        return retryable;
    }
}