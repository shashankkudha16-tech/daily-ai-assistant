package com.dailyai.assistant.integration;

import com.dailyai.assistant.entity.Article;

import java.util.Optional;

/**
 * Provider independent contract for generating a beginner friendly explanation.
 *
 * Implementations must never throw: when the provider is unavailable, rate limited,
 * times out or returns an unusable payload the method returns an empty Optional so
 * callers can keep the truthful offline explanation.
 */
public interface ExplanationProvider {

    /** True when the provider has the credentials it needs. */
    boolean isEnabled();

    /** True when the provider is configured and not currently backing off. */
    boolean isAvailable();

    Optional<ExplanationDraft> generateExplanation(Article article);

    /** Source-aware entry point. Providers that do not need the source material remain compatible. */
    default Optional<ExplanationDraft> generateExplanation(Article article, ArticleSourceMaterial source) {
        return generateExplanation(article);
    }
}
