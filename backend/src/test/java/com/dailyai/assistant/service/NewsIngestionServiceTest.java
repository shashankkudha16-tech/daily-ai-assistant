package com.dailyai.assistant.service;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.dto.RefreshResultDto;
import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.Category;
import com.dailyai.assistant.integration.ArticleCategorizer;
import com.dailyai.assistant.integration.ParsedArticle;
import com.dailyai.assistant.integration.RssFeedClient;
import com.dailyai.assistant.integration.RssParser;
import com.dailyai.assistant.repository.ArticleRepository;
import com.dailyai.assistant.repository.CategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NewsIngestionServiceTest {

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
    private ExplanationService explanationService;

    private AppProperties properties;
    private NewsIngestionService service;

    @BeforeEach
    void setUp() {
        properties = new AppProperties();
        AppProperties.Feed feed = new AppProperties.Feed();
        feed.setName("Test Feed");
        feed.setUrl("https://example.com/feed.xml");
        feed.setCategory("latest-ai-news");
        properties.getRss().setFeeds(List.of(feed));
        properties.getRss().setMaxItemsPerFeed(50);

        service = new NewsIngestionService(properties, feedClient, parser, categorizer,
                articleRepository, categoryRepository, explanationService);
    }

    @Test
    void skipsDuplicateUrlsAndSavesOnlyNewArticles() throws Exception {
        ParsedArticle first = new ParsedArticle("First", "https://example.com/a", "desc",
                Instant.now(), "Test Feed", "Author One", null);
        ParsedArticle duplicate = new ParsedArticle("Duplicate", "https://example.com/a", "desc",
                Instant.now(), "Test Feed", "Author Two", null);
        ParsedArticle known = new ParsedArticle("Known", "https://example.com/known", "desc",
                Instant.now(), "Test Feed", null, null);

        when(feedClient.fetch(anyString())).thenReturn("<rss/>".getBytes());
        when(parser.parse(any(byte[].class), anyString(), anyBoolean())).thenReturn(List.of(first, duplicate, known));
        when(articleRepository.existsByUrl("https://example.com/a")).thenReturn(false);
        when(articleRepository.existsByUrl("https://example.com/known")).thenReturn(true);
        when(categorizer.categorize(anyString(), anyString(), anyString())).thenReturn("generative-ai");
        when(categoryRepository.findBySlug("generative-ai"))
                .thenReturn(Optional.of(new Category("Generative AI", "generative-ai", null)));
        when(articleRepository.save(any(Article.class))).thenAnswer(invocation -> invocation.getArgument(0));

        RefreshResultDto result = service.refreshAll();

        assertThat(result.saved()).isEqualTo(1);
        assertThat(result.duplicates()).isEqualTo(2);
        assertThat(result.itemsRead()).isEqualTo(3);
        assertThat(result.feedsFailed()).isZero();
        assertThat(result.errors()).isEmpty();
        org.mockito.ArgumentCaptor<Article> saved = org.mockito.ArgumentCaptor.forClass(Article.class);
        org.mockito.Mockito.verify(articleRepository).save(saved.capture());
        assertThat(saved.getValue().getSource()).isEqualTo("Test Feed");
        assertThat(saved.getValue().getAuthor()).isEqualTo("Author One");
    }

    @Test
    void aFailingFeedDoesNotStopTheRun() throws Exception {
        AppProperties.Feed second = new AppProperties.Feed();
        second.setName("Broken Feed");
        second.setUrl("https://example.com/broken.xml");
        second.setCategory("ai-research");
        properties.getRss().setFeeds(List.of(properties.getRss().getFeeds().get(0), second));

        when(feedClient.fetch("https://example.com/feed.xml")).thenReturn("<rss/>".getBytes());
        when(parser.parse(any(byte[].class), anyString(), anyBoolean())).thenReturn(List.of());
        when(feedClient.fetch("https://example.com/broken.xml")).thenThrow(new IOException("HTTP 503"));

        RefreshResultDto result = service.refreshAll();

        assertThat(result.feedsSucceeded()).isEqualTo(1);
        assertThat(result.feedsFailed()).isEqualTo(1);
        assertThat(result.errors()).singleElement()
                .satisfies(error -> {
                    assertThat(error.feed()).isEqualTo("Broken Feed");
                    assertThat(error.message()).contains("503");
                });
    }

    @Test
    void rejectsUnsafeArticleUrls() throws Exception {
        ParsedArticle invalid = new ParsedArticle("Bad", "javascript:alert(1)", null,
                null, "Test Feed", null, null);
        when(feedClient.fetch(anyString())).thenReturn("<rss/>".getBytes());
        when(parser.parse(any(byte[].class), anyString(), anyBoolean())).thenReturn(List.of(invalid));

        RefreshResultDto result = service.refreshAll();

        assertThat(result.saved()).isZero();
        assertThat(result.skippedInvalid()).isEqualTo(1);
    }
}
