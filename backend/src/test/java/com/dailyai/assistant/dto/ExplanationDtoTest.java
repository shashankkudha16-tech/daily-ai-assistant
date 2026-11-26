package com.dailyai.assistant.dto;

import com.dailyai.assistant.entity.ArticleExplanation;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class ExplanationDtoTest {

    @Test
    void legacyClaimsWithoutProvenanceStillParse() {
        ArticleExplanation explanation = explanation();
        explanation.setClaimsJson("""
                [{"statement":"The source supports this.","evidence":"Exact quote."}]
                """);

        ExplanationDto dto = ExplanationDto.from(explanation);

        assertThat(dto.claims()).hasSize(1);
        assertThat(dto.claims().get(0).statement()).isEqualTo("The source supports this.");
        assertThat(dto.claims().get(0).section()).isNull();
        assertThat(dto.claims().get(0).kind()).isNull();
    }

    @Test
    void claimsWithProvenanceKeepAllFields() {
        ArticleExplanation explanation = explanation();
        explanation.setClaimsJson("""
                [{"section":"howItWorks","kind":"SOURCE_FACT","statement":"The study uses 56 worlds.","evidence":"We study this in 56 interactive worlds."}]
                """);

        ExplanationDto dto = ExplanationDto.from(explanation);

        assertThat(dto.claims()).hasSize(1);
        assertThat(dto.claims().get(0).section()).isEqualTo("howItWorks");
        assertThat(dto.claims().get(0).kind()).isEqualTo("SOURCE_FACT");
        assertThat(dto.claims().get(0).evidence()).isEqualTo("We study this in 56 interactive worlds.");
    }

    @Test
    void legacyNoActionFallbackDoesNotClaimTheSourceSaysNoActionIsRequired() {
        ArticleExplanation explanation = explanation();
        explanation.setRecommendedAction("No specific action is required. This update is mainly for your understanding.");

        assertThat(ExplanationDto.from(explanation).recommendedAction())
                .isEqualTo("Not available in the provided text.");
    }

    private ArticleExplanation explanation() {
        ArticleExplanation explanation = new ArticleExplanation();
        explanation.setGeneratedBy(ArticleExplanation.GeneratedBy.BASIC);
        explanation.setGeneratedAt(Instant.parse("2026-11-19T00:00:00Z"));
        return explanation;
    }
}
