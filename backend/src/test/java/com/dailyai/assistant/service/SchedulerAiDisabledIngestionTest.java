package com.dailyai.assistant.service;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.dto.RefreshResultDto;
import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.ArticleExplanation;
import com.dailyai.assistant.entity.Category;
import com.dailyai.assistant.integration.ArticleCategorizer;
import com.dailyai.assistant.integration.ExplanationProvider;
import com.dailyai.assistant.integration.ParsedArticle;
import com.dailyai.assistant.integration.PublisherArticleExtractor;
import com.dailyai.assistant.integration.NarrativeFactualityValidator;
import com.dailyai.assistant.integration.RssFeedClient;
import com.dailyai.assistant.integration.RssParser;
import com.dailyai.assistant.repository.ArticleExplanationRepository;
import com.dailyai.assistant.repository.ArticleRepository;
import com.dailyai.assistant.repository.CategoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SchedulerAiDisabledIngestionTest {

    @Mock
    private RssFeedClient feedClient;
    @Mock
    private RssParser parser;
    @Mock
    private ArticleCategorizer categorizer;
    @Mock
    private ArticleRepository articleRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private ArticleExplanationRepository explanationRepository;
    @Mock
    private ExplanationProvider provider;

    private NewsIngestionService ingestionService;
    private ExplanationService explanationService;

    @BeforeEach
    void setUp() {
        AppProperties properties = new AppProperties();
        AppProperties.Feed feed = new AppProperties.Feed();
        feed.setName("Test Feed");
        feed.setUrl("https://example.com/feed.xml");
        feed.setCategory("latest-ai-news");
        properties.getRss().setFeeds(List.of(feed));
        properties.getRss().setMaxItemsPerFeed(10);
        properties.getAi().setEnabled(false);
        properties.getAi().setApiKey("");

        explanationService = new ExplanationService(explanationRepository, provider, new ObjectMapper(), properties,
                new PublisherArticleContentPersistenceService(articleRepository, new PublisherArticleExtractor(properties)),
                new NarrativeFactualityValidator());
        ingestionService = new NewsIngestionService(properties, feedClient, parser, categorizer,
                articleRepository, categoryRepository, explanationService);

        when(explanationRepository.findByArticleId(any())).thenReturn(Optional.empty());
        when(explanationRepository.save(any(ArticleExplanation.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void refreshSavesBasicExplanationsWithoutCallingTheAiProvider() throws Exception {
        ParsedArticle candidate = new ParsedArticle("New model ships", "https://example.com/a",
                "A feed description.", Instant.now(), "Test Feed", null, null);

        when(feedClient.fetch(anyString())).thenReturn("<rss/>".getBytes());
        when(parser.parse(any(byte[].class), anyString(), org.mockito.ArgumentMatchers.anyBoolean())).thenReturn(List.of(candidate));
        when(articleRepository.existsByUrl("https://example.com/a")).thenReturn(false);
        when(articleRepository.save(any(Article.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(categorizer.categorize(anyString(), anyString(), anyString())).thenReturn("generative-ai");
        when(categoryRepository.findBySlug("generative-ai"))
                .thenReturn(Optional.of(new Category("Generative AI", "generative-ai", null)));

        RefreshResultDto result = ingestionService.refreshAll();

        assertThat(result.saved()).isEqualTo(1);
        ArgumentCaptor<ArticleExplanation> captor = ArgumentCaptor.forClass(ArticleExplanation.class);
        verify(explanationRepository).save(captor.capture());
        assertThat(captor.getValue().getGeneratedBy()).isEqualTo(ArticleExplanation.GeneratedBy.BASIC);
        assertThat(captor.getValue().getWhatHappened()).isEqualTo("A feed description.");
        assertThat(explanationService.isAiEnabled()).isFalse();
        verify(provider, never()).generateExplanation(any(Article.class));
    }
}
