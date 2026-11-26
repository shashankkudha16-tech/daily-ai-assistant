package com.dailyai.assistant.service;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.ArticleExplanation;
import com.dailyai.assistant.entity.Category;
import com.dailyai.assistant.integration.ExplanationDraft;
import com.dailyai.assistant.integration.ExplanationProvider;
import com.dailyai.assistant.integration.PublisherArticleExtractor;
import com.dailyai.assistant.integration.ArticleSourceMaterial;
import com.dailyai.assistant.integration.NarrativeFactualityValidator;
import com.dailyai.assistant.repository.ArticleExplanationRepository;
import com.dailyai.assistant.repository.ArticleRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExplanationServiceTest {

    @Mock
    private ArticleExplanationRepository explanationRepository;
    @Mock
    private ExplanationProvider provider;

    private ExplanationService service;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        properties.getAi().setRetryAfterMinutes(360);
        service = new ExplanationService(explanationRepository, provider, new ObjectMapper(), properties,
                new PublisherArticleContentPersistenceService(org.mockito.Mockito.mock(ArticleRepository.class),
                        new PublisherArticleExtractor(properties)), new NarrativeFactualityValidator());
        org.mockito.Mockito.lenient()
                .when(explanationRepository.save(any(ArticleExplanation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void withoutProviderItStillCreatesTheOfflineExplanation() {
        when(provider.isEnabled()).thenReturn(false);
        when(explanationRepository.findByArticleId(1L)).thenReturn(Optional.empty());

        ArticleExplanation explanation = service.getOrCreate(article(), true);

        assertThat(explanation.getGeneratedBy()).isEqualTo(ArticleExplanation.GeneratedBy.BASIC);
        assertThat(explanation.getWhatHappened()).isEqualTo("A short feed description.");
        assertThat(explanation.getTechnology()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getHowItWorks()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getWhatHappened()).isEqualTo("A short feed description.");
        assertThat(explanation.getImportance()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getPracticalExample()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getFresherLearning()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getWhyItHappened()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getUsefulToReader()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getRecommendedAction()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getSourceType()).isEqualTo("EXCERPT");
        assertThat(explanation.getWhoBenefits()).isEqualTo("Not available in the provided text.");
        assertThat(service.isAiEnabled()).isFalse();
    }

    @Test
    void providerDraftUpgradesTheExplanationWithClaimsAndUsage() {
        when(provider.isEnabled()).thenReturn(true);
        when(provider.isAvailable()).thenReturn(true);
        when(explanationRepository.findByArticleId(1L)).thenReturn(Optional.empty());
        when(provider.generateExplanation(any(Article.class), any(ArticleSourceMaterial.class))).thenReturn(Optional.of(draft()));

        ArticleExplanation explanation = service.getOrCreate(article(), true);

        assertThat(explanation.getGeneratedBy()).isEqualTo(ArticleExplanation.GeneratedBy.AI);
        assertThat(explanation.getWhatHappened()).isEqualTo("New model ships.");
        assertThat(explanation.getWhoBenefits()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getTechnicalExplanation()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getWhyItHappened()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getUsefulToReader()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getRecommendedAction()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getSourceType()).isEqualTo("EXCERPT");
        assertThat(explanation.getClaimsJson()).contains("A short feed description.");
        assertThat(explanation.getProvider()).isEqualTo("mock");
        assertThat(explanation.getModel()).isEqualTo("mock-model");
        assertThat(explanation.getInputTokens()).isEqualTo(120);
        assertThat(explanation.getOutputTokens()).isEqualTo(80);
        assertThat(explanation.getAiAttempts()).isEqualTo(1);
        assertThat(explanation.getAiAttemptedAt()).isNotNull();
        assertThat(explanation.getGeneratedAt()).isNotNull();
    }

    @Test
    void providerFailureKeepsTheOfflineExplanationStored() {
        when(provider.isEnabled()).thenReturn(true);
        when(provider.isAvailable()).thenReturn(true);
        ArticleExplanation existing = new ArticleExplanation();
        existing.setId(5L);
        existing.setArticle(article());
        existing.setWhatHappened("Offline text.");
        existing.setGeneratedBy(ArticleExplanation.GeneratedBy.BASIC);
        when(explanationRepository.findByArticleId(1L)).thenReturn(Optional.of(existing));
        when(provider.generateExplanation(any(Article.class), any(ArticleSourceMaterial.class))).thenReturn(Optional.empty());

        ArticleExplanation explanation = service.getOrCreate(article(), true);

        assertThat(explanation.getGeneratedBy()).isEqualTo(ArticleExplanation.GeneratedBy.BASIC);
        assertThat(explanation.getWhatHappened()).isEqualTo("A short feed description.");
        assertThat(explanation.getWhereUsed()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getAiAttemptedAt()).isNotNull();
    }

    @Test
    void incompleteOrUnanchoredProviderDraftKeepsTheOfflineExplanation() {
        when(provider.isEnabled()).thenReturn(true);
        when(provider.isAvailable()).thenReturn(true);
        when(explanationRepository.findByArticleId(1L)).thenReturn(Optional.empty());
        when(provider.generateExplanation(any(Article.class), any(ArticleSourceMaterial.class))).thenReturn(Optional.of(new ExplanationDraft(
                "Unsupported headline", "Technology", "Importance", "How", "Where", "Learn", "Example", "Related",
                "quote not present in the article", "Who", "Use", "Technical",
                List.of(new ExplanationDraft.Claim("Unsupported headline", "quote not present in the article")),
                new ExplanationDraft.Metadata("mock", "mock-model", 1, 1, 1))));

        ArticleExplanation explanation = service.getOrCreate(article(), true);

        assertThat(explanation.getGeneratedBy()).isEqualTo(ArticleExplanation.GeneratedBy.BASIC);
        assertThat(explanation.getWhatHappened()).isEqualTo("A short feed description.");
    }

    @Test
    void failedArticleIsNotRetriedInsideTheRetryWindow() {
        when(provider.isEnabled()).thenReturn(true);
        when(provider.isAvailable()).thenReturn(true);
        ArticleExplanation existing = new ArticleExplanation();
        existing.setId(7L);
        existing.setArticle(article());
        existing.setGeneratedBy(ArticleExplanation.GeneratedBy.BASIC);
        existing.setAiAttemptedAt(Instant.now());
        existing.setAiAttempts(1);
        when(explanationRepository.findByArticleId(1L)).thenReturn(Optional.of(existing));

        service.getOrCreate(article(), true);

        verify(provider, never()).generateExplanation(any(Article.class), any(ArticleSourceMaterial.class));
    }

    @Test
    void manualRegenerateBypassesTheRetryWindow() {
        when(provider.isEnabled()).thenReturn(true);
        when(provider.isAvailable()).thenReturn(true);
        ArticleExplanation existing = new ArticleExplanation();
        existing.setId(8L);
        existing.setArticle(article());
        existing.setGeneratedBy(ArticleExplanation.GeneratedBy.BASIC);
        existing.setAiAttemptedAt(Instant.now());
        existing.setAiAttempts(1);
        when(explanationRepository.findByArticleId(1L)).thenReturn(Optional.of(existing));
        when(provider.generateExplanation(any(Article.class), any(ArticleSourceMaterial.class))).thenReturn(Optional.of(draft()));

        ArticleExplanation explanation = service.regenerateWithAi(article());

        assertThat(explanation.getGeneratedBy()).isEqualTo(ArticleExplanation.GeneratedBy.AI);
        assertThat(explanation.getAiAttempts()).isEqualTo(2);
    }

    @Test
    void regenerateWithoutProviderDoesNotThrow() {
        when(provider.isEnabled()).thenReturn(false);
        when(explanationRepository.findByArticleId(1L)).thenReturn(Optional.empty());

        ArticleExplanation explanation = service.regenerateWithAi(article());

        ArgumentCaptor<ArticleExplanation> captor = ArgumentCaptor.forClass(ArticleExplanation.class);
        verify(explanationRepository, org.mockito.Mockito.atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getGeneratedBy()).isEqualTo(ArticleExplanation.GeneratedBy.BASIC);
        assertThat(explanation.getGeneratedBy()).isEqualTo(ArticleExplanation.GeneratedBy.BASIC);
    }

    @Test
    void missingPublisherDescriptionDoesNotInventArticleFacts() {
        when(explanationRepository.findByArticleId(1L)).thenReturn(Optional.empty());
        Article article = article();
        article.setDescription("  ");

        ArticleExplanation explanation = service.createBasic(article);

        assertThat(explanation.getWhatHappened()).isEqualTo(article.getTitle());
        assertThat(explanation.getSourceType()).isEqualTo("TITLE_ONLY");
        assertThat(explanation.getHowItWorks()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getGeneratedBy()).isEqualTo(ArticleExplanation.GeneratedBy.BASIC);
    }

    @Test
    void researchBasicFallbackDoesNotInventImportance() {
        Article article = article();
        article.setCategory(new Category("AI Research", "ai-research", null));
        when(explanationRepository.findByArticleId(1L)).thenReturn(Optional.empty());

        ArticleExplanation explanation = service.createBasic(article);

        assertThat(explanation.getImportance()).isEqualTo("Not available in the provided text.");
    }

    @Test
    void basicFallbackTruncatesLongFeedTextWithoutAddingFacts() {
        when(explanationRepository.findByArticleId(1L)).thenReturn(Optional.empty());
        Article article = article();
        article.setDescription("Fact. " + "ordinary source wording ".repeat(40));

        ArticleExplanation explanation = service.createBasic(article);

        assertThat(explanation.getWhatHappened()).hasSizeLessThanOrEqualTo(500).endsWith("…");
        assertThat(explanation.getWhatHappened()).startsWith("Fact.");
    }

    private ExplanationDraft draft() {
        return new ExplanationDraft(
                "New model ships.",
                "Not available in the provided text.",
                "A short feed description.",
                "A short feed description.",
                "Not available in the provided text.",
                "Not available in the provided text.",
                "Not available in the provided text.",
                "Not available in the provided text.",
                "A short feed description.",
                "Not available in the provided text.",
                "Not available in the provided text.",
                "Not available in the provided text.",
                "Not available in the provided text.",
                "Not available in the provided text.",
                "Not available in the provided text.",
                List.of(new ExplanationDraft.Claim("A short feed description.", "A short feed description.")),
                new ExplanationDraft.Metadata("mock", "mock-model", 120, 80, 42));
    }

    private Article article() {
        Category category = new Category("Generative AI", "generative-ai", null);
        Article article = new Article();
        article.setId(1L);
        article.setTitle("New model ships");
        article.setUrl("https://example.com/a");
        article.setDescription("A short feed description.");
        article.setCategory(category);
        return article;
    }
}
