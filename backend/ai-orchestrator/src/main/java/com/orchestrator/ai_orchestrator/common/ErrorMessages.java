package com.orchestrator.ai_orchestrator.common;

/**
 * Turns any exception into a short, never-null, human-readable string.
 *
 * <p>Why this exists: {@code Throwable.getMessage()} can return null, and
 * {@code Map.of(...)} throws NullPointerException on a null value. Without this helper,
 * an error handler can crash while handling an error and hide the original failure.
 */
public final class ErrorMessages {

    private static final int MAX_LENGTH = 1000;

    private ErrorMessages() {
        // Static utility class: not meant to be instantiated.
    }

    /**
     * Returns "ClassName: message", plus the root cause when the exception wraps another one.
     * Wrapped exceptions (e.g. "Planning failed for jobId") would otherwise hide the real reason.
     */
    public static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }

        String text = summarize(error);

        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        if (root != error) {
            text = text + " | root cause: " + summarize(root);
        }

        return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
    }

    private static String summarize(Throwable error) {
        String message = error.getMessage();
        return (message == null || message.isBlank())
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message;
    }
}