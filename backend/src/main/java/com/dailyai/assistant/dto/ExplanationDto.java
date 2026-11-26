package com.dailyai.assistant.dto;

import com.dailyai.assistant.entity.ArticleExplanation;
import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.integration.ArticleSourceMaterial;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

public record ExplanationDto(
        String generatedBy,
        Instant generatedAt,
        String whatHappened,
        String technology,
        String importance,
        String howItWorks,
        String whereUsed,
        String fresherLearning,
        String practicalExample,
        String relatedTechnologies,
        String whoBenefits,
        String useCases,
        String technicalExplanation,
        String sourceEvidence,
        String whyItHappened,
        String usefulToReader,
        String recommendedAction,
        String sourceType,
        String sourceCompleteness,
        List<ClaimDto> claims,
        String provider,
        String model,
        Integer inputTokens,
        Integer outputTokens,
        Integer latencyMs
) {

    public record ClaimDto(String section, String kind, String statement, String evidence) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String NOT_AVAILABLE = "Not available in the provided text.";
    private static final String LEGACY_NO_ACTION_FALLBACK =
            "No specific action is required. This update is mainly for your understanding.";

    public static ExplanationDto from(ArticleExplanation explanation) {
        return from(explanation, null);
    }

    public static ExplanationDto from(ArticleExplanation explanation, Article article) {
        if (explanation == null) {
            return null;
        }
        ArticleSourceMaterial availableSource = ArticleSourceMaterial.fromFeed(article);
        return new ExplanationDto(
                explanation.getGeneratedBy().name(),
                explanation.getGeneratedAt(),
                explanation.getWhatHappened(),
                explanation.getTechnology(),
                explanation.getImportance(),
                explanation.getHowItWorks(),
                explanation.getWhereUsed(),
                explanation.getFresherLearning(),
                explanation.getPracticalExample(),
                explanation.getRelatedTechnologies(),
                explanation.getWhoBenefits(),
                explanation.getUseCases(),
                explanation.getTechnicalExplanation(),
                explanation.getSourceEvidence(),
                fallback(explanation.getWhyItHappened(), "The article does not say why this happened."),
                fallback(explanation.getUsefulToReader(), "The article does not describe a specific personal benefit."),
                recommendedAction(explanation.getRecommendedAction()),
                fallback(explanation.getSourceType(), availableSource.type().name()),
                fallback(explanation.getSourceCompleteness(), availableSource.completeness().name()),
                parseClaims(explanation.getClaimsJson()),
                explanation.getProvider(),
                explanation.getModel(),
                explanation.getInputTokens(),
                explanation.getOutputTokens(),
                explanation.getLatencyMs()
        );
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String recommendedAction(String value) {
        if (value == null || value.isBlank() || LEGACY_NO_ACTION_FALLBACK.equals(value)) return NOT_AVAILABLE;
        return value;
    }

    private static List<ClaimDto> parseClaims(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<ClaimDto>>() {
            });
        } catch (Exception ignored) {
            // Bad stored JSON must not break the article page.
            return List.of();
        }
    }
}
