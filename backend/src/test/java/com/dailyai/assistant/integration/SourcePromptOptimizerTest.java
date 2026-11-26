package com.dailyai.assistant.integration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SourcePromptOptimizerTest {
    @Test
    void staysWithinEstimatedBudgetAndStopsOnlyAtSentenceBoundary() {
        String first = "The trial did not show that the model is faster.";
        String second = "The authors said the result remains uncertain.";
        String third = "A later study will include more participants.";
        ArticleSourceMaterial input = new ArticleSourceMaterial(ArticleSourceMaterial.Type.FULL_ARTICLE,
                ArticleSourceMaterial.Completeness.COMPLETE, first + " " + second + " " + third);

        ArticleSourceMaterial selected = SourcePromptOptimizer.optimize(input, 16);

        assertThat(selected.text()).isEqualTo(first);
        assertThat(SourcePromptOptimizer.estimateTokens(selected.text())).isLessThanOrEqualTo(16);
        assertThat(selected.completeness()).isEqualTo(ArticleSourceMaterial.Completeness.TRUNCATED);
        assertThat(selected.text()).doesNotContain(second, "A later study");
    }

    @Test
    void removesOnlyExactRepeatedSentencesAndPreservesNegationAndQualifications() {
        String first = "The trial did not show that the model is faster.";
        String qualification = "The authors said the result remains uncertain for older devices.";
        ArticleSourceMaterial input = new ArticleSourceMaterial(ArticleSourceMaterial.Type.FULL_ARTICLE,
                ArticleSourceMaterial.Completeness.COMPLETE,
                first + " " + qualification + " " + first);

        ArticleSourceMaterial selected = SourcePromptOptimizer.optimize(input, 100);

        assertThat(selected.text()).isEqualTo(first + " " + qualification);
        assertThat(selected.completeness()).isEqualTo(ArticleSourceMaterial.Completeness.COMPLETE);
        assertThat(selected.text()).contains("did not", "uncertain", "older devices");
    }

    @Test
    void keepsSourceTypeSeparateFromCompleteness() {
        ArticleSourceMaterial input = new ArticleSourceMaterial(ArticleSourceMaterial.Type.RSS_CONTENT,
                ArticleSourceMaterial.Completeness.PARTIAL,
                "One. Two. Three. Four. Five. Six. Seven. Eight. Nine. Ten.");

        ArticleSourceMaterial selected = SourcePromptOptimizer.optimize(input, 2);

        assertThat(selected.type()).isEqualTo(ArticleSourceMaterial.Type.RSS_CONTENT);
        assertThat(selected.completeness()).isEqualTo(ArticleSourceMaterial.Completeness.TRUNCATED);
    }
}
