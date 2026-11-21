package com.orchestrator.ai_orchestrator.common;

/** Small string helpers shared by several modules. */
public final class TextUtils {

    private TextUtils() {
        // Static utility class: not meant to be instantiated.
    }

    /**
     * Cuts {@code text} to at most {@code maxChars} characters and marks the cut.
     * Used to keep prompts, logs and stored results bounded in size.
     * A null input returns an empty string so callers never need a null check.
     */
    public static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxChars ? text : text.substring(0, maxChars) + "... [truncated]";
    }
}