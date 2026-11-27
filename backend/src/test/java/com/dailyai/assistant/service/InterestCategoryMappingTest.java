package com.dailyai.assistant.service;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class InterestCategoryMappingTest {
    @Test
    void mapsPreferenceIdsToStoredArticleSlugs() {
        assertThat(InterestCategoryMapping.categoriesForPreferences(List.of("GENERATIVE_AI")))
                .contains("generative-ai", "ai-models")
                .doesNotContain("GENERATIVE_AI");
        assertThat(InterestCategoryMapping.categoriesForPreferences(List.of("JAVA_BACKEND")))
                .contains("ai-tools", "tutorials");
    }

    @Test
    void mapsCareerContextToRelevantNewsCategories() {
        assertThat(InterestCategoryMapping.categoriesForCareer("Java Backend Developer", "Java Developer"))
                .contains("ai-tools", "tutorials", "ai-agents");
        assertThat(InterestCategoryMapping.categoriesForCareer("Unspecified", ""))
                .isEmpty();
    }
}
