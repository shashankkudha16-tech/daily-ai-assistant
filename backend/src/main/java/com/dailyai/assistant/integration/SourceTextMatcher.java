package com.dailyai.assistant.integration;

import java.util.Locale;

/** Exact normalized wording check. It intentionally rejects paraphrases rather than treating overlap as entailment. */
final class SourceTextMatcher {
    private SourceTextMatcher() {}

    static boolean contains(String source, String statement) {
        String normalizedSource = normalize(source);
        String normalizedStatement = normalize(statement);
        return !normalizedStatement.isBlank() && (" " + normalizedSource + " ").contains(" " + normalizedStatement + " ");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", " ").trim().replaceAll("\\s+", " ");
    }
}
