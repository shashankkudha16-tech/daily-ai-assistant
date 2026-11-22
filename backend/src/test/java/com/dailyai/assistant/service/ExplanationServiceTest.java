package com.dailyai.assistant.service;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.ArticleExplanation;
import com.dailyai.assistant.entity.Category;
import com.dailyai.assistant.integration.ExplanationDraft;
import com.dailyai.assistant.integration.ExplanationProvider;
import com.dailyai.assistant.repository.ArticleExplanationRepository;
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
        service = new ExplanationService(explanationRepository, provider, new ObjectMapper(), properties);
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
        assertThat(explanation.getImportance()).contains("follow changes in AI").doesNotContain("becoming useful");
        assertThat(explanation.getPracticalExample()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getFresherLearning()).startsWith("Key takeaway:");
        assertThat(explanation.getWhoBenefits()).contains("does not name a specific audience");
        assertThat(service.isAiEnabled()).isFalse();
    }

    @Test
    void providerDraftUpgradesTheExplanationWithClaimsAndUsage() {
        when(provider.isEnabled()).thenReturn(true);
        when(explanationRepository.findByArticleId(1L)).thenReturn(Optional.empty());
        when(provider.generateExplanation(any(Article.class))).thenReturn(Optional.of(draft()));

        ArticleExplanation explanation = service.getOrCreate(article(), true);

        assertThat(explanation.getGeneratedBy()).isEqualTo(ArticleExplanation.GeneratedBy.AI);
        assertThat(explanation.getWhatHappened()).isEqualTo("A new model shipped.");
        assertThat(explanation.getWhoBenefits()).isEqualTo("Developers comparing models.");
        assertThat(explanation.getTechnicalExplanation()).isEqualTo("A model predicts the next token.");
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
        ArticleExplanation existing = new ArticleExplanation();
        existing.setId(5L);
        existing.setArticle(article());
        existing.setWhatHappened("Offline text.");
        existing.setGeneratedBy(ArticleExplanation.GeneratedBy.BASIC);
        when(explanationRepository.findByArticleId(1L)).thenReturn(Optional.of(existing));
        when(provider.generateExplanation(any(Article.class))).thenReturn(Optional.empty());

        ArticleExplanation explanation = service.getOrCreate(article(), true);

        assertThat(explanation.getGeneratedBy()).isEqualTo(ArticleExplanation.GeneratedBy.BASIC);
        assertThat(explanation.getWhatHappened()).isEqualTo("A short feed description.");
        assertThat(explanation.getWhereUsed()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getAiAttemptedAt()).isNotNull();
    }

    @Test
    void incompleteOrUnanchoredProviderDraftKeepsTheOfflineExplanation() {
        when(provider.isEnabled()).thenReturn(true);
        when(explanationRepository.findByArticleId(1L)).thenReturn(Optional.empty());
        when(provider.generateExplanation(any(Article.class))).thenReturn(Optional.of(new ExplanationDraft(
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
        ArticleExplanation existing = new ArticleExplanation();
        existing.setId(7L);
        existing.setArticle(article());
        existing.setGeneratedBy(ArticleExplanation.GeneratedBy.BASIC);
        existing.setAiAttemptedAt(Instant.now());
        existing.setAiAttempts(1);
        when(explanationRepository.findByArticleId(1L)).thenReturn(Optional.of(existing));

        service.getOrCreate(article(), true);

        verify(provider, never()).generateExplanation(any(Article.class));
    }

    @Test
    void manualRegenerateBypassesTheRetryWindow() {
        when(provider.isEnabled()).thenReturn(true);
        ArticleExplanation existing = new ArticleExplanation();
        existing.setId(8L);
        existing.setArticle(article());
        existing.setGeneratedBy(ArticleExplanation.GeneratedBy.BASIC);
        existing.setAiAttemptedAt(Instant.now());
        existing.setAiAttempts(1);
        when(explanationRepository.findByArticleId(1L)).thenReturn(Optional.of(existing));
        when(provider.generateExplanation(any(Article.class))).thenReturn(Optional.of(draft()));

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

        assertThat(explanation.getWhatHappened()).contains("feed has no summary")
                .contains(article.getTitle());
        assertThat(explanation.getHowItWorks()).isEqualTo("Not available in the provided text.");
        assertThat(explanation.getGeneratedBy()).isEqualTo(ArticleExplanation.GeneratedBy.BASIC);
    }

    @Test
    void researchImportanceExplainsThatAnAbstractIsNotProductReadinessEvidence() {
        Article article = article();
        article.setCategory(new Category("AI Research", "ai-research", null));
        when(explanationRepository.findByArticleId(1L)).thenReturn(Optional.empty());

        ArticleExplanation explanation = service.createBasic(article);

        assertThat(explanation.getImportance()).contains("research result", "does not show", "limits");
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
                "A new model shipped.",
                "A neural network.",
                "It is faster.",
                "It predicts tokens.",
                "Chat assistants.",
                "Learn transformers.",
                "Build a demo.",
                "LLMs, RAG",
                "A short feed description.",
                "Developers comparing models.",
                "Try a small summarisation feature.",
                "A model predicts the next token.",
                List.of(new ExplanationDraft.Claim("A new model shipped.", "A short feed description.")),
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
