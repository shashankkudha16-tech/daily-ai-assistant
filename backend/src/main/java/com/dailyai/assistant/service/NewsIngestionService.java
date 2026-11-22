package com.dailyai.assistant.service;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.dto.RefreshResultDto;
import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.Category;
import com.dailyai.assistant.exception.BadRequestException;
import com.dailyai.assistant.integration.ArticleCategorizer;
import com.dailyai.assistant.integration.ParsedArticle;
import com.dailyai.assistant.integration.RssFeedClient;
import com.dailyai.assistant.integration.RssParser;
import com.dailyai.assistant.repository.ArticleRepository;
import com.dailyai.assistant.repository.CategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class NewsIngestionService {

    private static final Logger log = LoggerFactory.getLogger(NewsIngestionService.class);
    private static final int MAX_URL_LENGTH = 900;

    private final AppProperties properties;
    private final RssFeedClient feedClient;
    private final RssParser parser;
    private final ArticleCategorizer categorizer;
    private final ArticleRepository articleRepository;
    private final CategoryRepository categoryRepository;
    private final ExplanationService explanationService;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile Instant lastSuccessfulRun;

    public NewsIngestionService(AppProperties properties,
                                RssFeedClient feedClient,
                                RssParser parser,
                                ArticleCategorizer categorizer,
                                ArticleRepository articleRepository,
                                CategoryRepository categoryRepository,
                                ExplanationService explanationService) {
        this.properties = properties;
        this.feedClient = feedClient;
        this.parser = parser;
        this.categorizer = categorizer;
        this.articleRepository = articleRepository;
        this.categoryRepository = categoryRepository;
        this.explanationService = explanationService;
    }

    /**
     * Fetches every configured feed once. A failure in one feed never stops the others,
     * and articles are de-duplicated by their original URL.
     */
    public RefreshResultDto refreshAll() {
        if (!running.compareAndSet(false, true)) {
            throw new BadRequestException("A news refresh is already running. Try again in a moment.");
        }
        try {
            List<AppProperties.Feed> feeds = properties.getRss().getFeeds();
            List<RefreshResultDto.FeedError> errors = new ArrayList<>();
            Map<String, Category> categoryCache = new HashMap<>();

            int attempted = feeds.size();
            int succeeded = 0;
            int itemsRead = 0;
            int saved = 0;
            int duplicates = 0;
            int skipped = 0;

            for (AppProperties.Feed feed : feeds) {
                try {
                    byte[] body = feedClient.fetch(feed.getUrl());
                    List<ParsedArticle> articles = parser.parse(body, feed.getName());
                    itemsRead += articles.size();

                    int limit = Math.min(articles.size(), properties.getRss().getMaxItemsPerFeed());
                    Set<String> seenInRun = new HashSet<>();

                    for (int i = 0; i < limit; i++) {
                        ParsedArticle candidate = articles.get(i);
                        String validationError = validate(candidate);
                        if (validationError != null) {
                            skipped++;
                            log.debug("Skipping {} from {}: {}", candidate.url(), feed.getName(), validationError);
                            continue;
                        }
                        if (!seenInRun.add(candidate.url())) {
                            duplicates++;
                            continue;
                        }
                        if (articleRepository.existsByUrl(candidate.url())) {
                            duplicates++;
                            continue;
                        }

                        try {
                            Article entity = toEntity(candidate, feed, categoryCache);
                            Article stored = articleRepository.save(entity);
                            explanationService.createBasic(stored);
                            saved++;
                        } catch (RuntimeException ex) {
                            // A race on the unique URL index or bad row must not abort the feed.
                            duplicates++;
                            log.warn("Could not store article {}: {}", candidate.url(), ex.getMessage());
                        }
                    }
                    succeeded++;
                    log.info("Feed {}: {} entries read, {} stored so far", feed.getName(), articles.size(), saved);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    errors.add(new RefreshResultDto.FeedError(feed.getName(), "Interrupted"));
                    log.warn("Feed {} fetch interrupted", feed.getName());
                } catch (Exception ex) {
                    errors.add(new RefreshResultDto.FeedError(feed.getName(), safeMessage(ex)));
                    log.warn("Feed {} failed: {}", feed.getName(), safeMessage(ex));
                }
            }

            lastSuccessfulRun = Instant.now();
            return new RefreshResultDto(lastSuccessfulRun, attempted, succeeded, attempted - succeeded,
                    itemsRead, saved, duplicates, skipped, errors);
        } finally {
            running.set(false);
        }
    }

    public Instant getLastSuccessfulRun() {
        return lastSuccessfulRun;
    }

    public boolean isRunning() {
        return running.get();
    }

    private String validate(ParsedArticle article) {
        if (article.title() == null || article.title().isBlank()) {
            return "missing title";
        }
        if (article.url() == null || article.url().length() > MAX_URL_LENGTH) {
            return "url missing or too long";
        }
        if (!article.url().startsWith("http://") && !article.url().startsWith("https://")) {
            return "unsupported url scheme";
        }
        return null;
    }

    private Article toEntity(ParsedArticle candidate, AppProperties.Feed feed, Map<String, Category> cache) {
        String slug = categorizer.categorize(candidate.title(), candidate.description(), feed.getCategory());
        Category category = cache.computeIfAbsent(slug, key -> resolveCategory(key, feed.getCategory()));

        Article article = new Article();
        article.setTitle(candidate.title().length() > 500
                ? candidate.title().substring(0, 500)
                : candidate.title());
        article.setUrl(candidate.url());
        article.setDescription(candidate.description());
        article.setSource(candidate.source() == null || candidate.source().isBlank()
                ? feed.getName()
                : candidate.source());
        article.setAuthor(candidate.author());
        article.setPublishedAt(candidate.publishedAt());
        article.setImageUrl(truncate(candidate.imageUrl(), 1000));
        article.setFetchedAt(Instant.now());
        article.setCategory(category);
        return article;
    }

    private Category resolveCategory(String slug, String fallbackSlug) {
        Optional<Category> category = categoryRepository.findBySlug(slug);
        if (category.isPresent()) {
            return category.get();
        }
        if (fallbackSlug != null && !fallbackSlug.equals(slug)) {
            Optional<Category> fallback = categoryRepository.findBySlug(fallbackSlug);
            if (fallback.isPresent()) {
                return fallback.get();
            }
        }
        return categoryRepository.findBySlug("latest-ai-news").orElse(null);
    }

    private String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private String safeMessage(Exception ex) {
        String message = ex.getMessage();
        return message == null || message.isBlank() ? ex.getClass().getSimpleName() : message;
    }
}
