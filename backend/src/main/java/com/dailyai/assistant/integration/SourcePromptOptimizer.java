package com.dailyai.assistant.integration;

import java.text.BreakIterator;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Reduces repeated source text and applies a sentence-boundary prompt budget. */
final class SourcePromptOptimizer {
    private static final int CHARS_PER_ESTIMATED_TOKEN = 4;

    private SourcePromptOptimizer() {}

    static ArticleSourceMaterial optimize(ArticleSourceMaterial source, int maxSourceTokens) {
        if (source == null || source.text().isBlank()) return source;
        int maxChars = Math.max(1, maxSourceTokens > 0 ? maxSourceTokens : 4500)
                * CHARS_PER_ESTIMATED_TOKEN;
        BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ROOT);
        sentences.setText(source.text());
        Set<String> seen = new HashSet<>();
        StringBuilder selected = new StringBuilder();
        int start = sentences.first();
        boolean truncated = false;
        for (int end = sentences.next(); end != BreakIterator.DONE; start = end, end = sentences.next()) {
            String sentence = source.text().substring(start, end).trim();
            if (sentence.isBlank()) continue;
            String key = sentence.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
            if (!seen.add(key)) continue;
            int addition = sentence.length() + (selected.isEmpty() ? 0 : 1);
            if (selected.length() + addition > maxChars) {
                truncated = true;
                break;
            }
            if (!selected.isEmpty()) selected.append(' ');
            selected.append(sentence);
        }
        String text = selected.toString();
        ArticleSourceMaterial.Completeness completeness = truncated
                ? ArticleSourceMaterial.Completeness.TRUNCATED : source.completeness();
        return new ArticleSourceMaterial(source.type(), completeness, text);
    }

    static int estimateTokens(String text) {
        return text == null ? 0 : (int) Math.ceil(text.length() / (double) CHARS_PER_ESTIMATED_TOKEN);
    }
}
