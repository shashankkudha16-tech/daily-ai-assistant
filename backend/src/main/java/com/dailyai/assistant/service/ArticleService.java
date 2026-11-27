package com.dailyai.assistant.service;

import com.dailyai.assistant.dto.ArticleDetailDto;
import com.dailyai.assistant.dto.ArticleDto;
import com.dailyai.assistant.dto.PageResponse;
import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.Bookmark;
import com.dailyai.assistant.entity.ReadingHistory;
import com.dailyai.assistant.entity.User;
import com.dailyai.assistant.exception.BadRequestException;
import com.dailyai.assistant.exception.ResourceNotFoundException;
import com.dailyai.assistant.repository.ArticleRepository;
import com.dailyai.assistant.repository.ArticleSpecifications;
import com.dailyai.assistant.repository.CategoryRepository;
import com.dailyai.assistant.repository.ReadingHistoryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class ArticleService {

    private static final int MAX_PAGE_SIZE = 50;

    private final ArticleRepository articleRepository;
    private final CategoryRepository categoryRepository;
    private final BookmarkService bookmarkService;
    private final ReadingHistoryRepository readingHistoryRepository;
    private final ExplanationService explanationService;
    private final CurrentUserService currentUserService;
    private final ArticleUsefulnessEngine usefulnessEngine;
    private final ResearchSummaryBuilder researchSummaryBuilder;
    private final ArticleAiGenerationGuard aiGenerationGuard;

    public ArticleService(ArticleRepository articleRepository,
                          CategoryRepository categoryRepository,
                          BookmarkService bookmarkService,
                          ReadingHistoryRepository readingHistoryRepository,
                          ExplanationService explanationService,
                          CurrentUserService currentUserService,
                          ArticleUsefulnessEngine usefulnessEngine,
                          ResearchSummaryBuilder researchSummaryBuilder,
                          ArticleAiGenerationGuard aiGenerationGuard) {
        this.articleRepository = articleRepository;
        this.categoryRepository = categoryRepository;
        this.bookmarkService = bookmarkService;
        this.readingHistoryRepository = readingHistoryRepository;
        this.explanationService = explanationService;
        this.currentUserService = currentUserService;
        this.usefulnessEngine = usefulnessEngine;
        this.researchSummaryBuilder = researchSummaryBuilder;
        this.aiGenerationGuard = aiGenerationGuard;
    }

    @Transactional(readOnly = true)
    public PageResponse<ArticleDto> list(String category, String sort, int page, int size) {
        return list(category, null, null, sort, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<ArticleDto> list(String category, String region, String period, String sort, int page, int size) {
        Pageable pageable = pageable(page, size, sort);
        Set<Long> bookmarked = bookmarkService.bookmarkedIds(Bookmark.ItemType.ARTICLE);
        String slug = category == null || category.isBlank() || "all".equalsIgnoreCase(category)
                ? null : requireCategory(category);
        Page<Article> result = articleRepository.findAll(
                ArticleSpecifications.filters(slug, normalizeRegion(region), periodStart(period), null), pageable);
        return PageResponse.from(result, article -> toDto(article, bookmarked.contains(article.getId())));
    }

    @Transactional(readOnly = true)
    public List<ArticleDto> latest(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        Pageable pageable = PageRequest.of(0, safeLimit);
        List<Article> articles = articleRepository.findByPublishedAtIsNotNullOrderByPublishedAtDesc(pageable);
        if (articles.isEmpty()) {
            articles = articleRepository
                    .findAll(PageRequest.of(0, safeLimit, Sort.by(Sort.Order.desc("fetchedAt"))))
                    .getContent();
        }
        Set<Long> bookmarked = bookmarkService.bookmarkedIds(Bookmark.ItemType.ARTICLE);
        return articles.stream()
                .map(article -> toDto(article, bookmarked.contains(article.getId())))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ArticleDto> latestForRegion(String region, int limit) {
        String safeRegion = normalizeRegion(region);
        int safeLimit = Math.max(1, Math.min(limit, MAX_PAGE_SIZE));
        List<Article> page = articleRepository.findByRegionAndPublishedAtIsNotNullOrderByPublishedAtDesc(safeRegion,
                PageRequest.of(0, safeLimit));
        Set<Long> bookmarked = bookmarkService.bookmarkedIds(Bookmark.ItemType.ARTICLE);
        return page.stream().map(article -> toDto(article, bookmarked.contains(article.getId()))).toList();
    }

    @Transactional
    public ArticleDetailDto get(Long id) {
        Article article = requireArticle(id);
        Boolean bookmarked = bookmarkService.isBookmarked(Bookmark.ItemType.ARTICLE, id);
        var currentUser = currentUserService.getCurrentUserOrNull();
        // Public article reads keep working and still receive any cached AI text,
        // but only an authenticated reader can start a new billable generation.
        boolean allowNewAi = currentUser.isPresent();
        var explanation = aiGenerationGuard.run(id,
                () -> explanationService.getOrCreate(article, allowNewAi));
        recordRead(article);
        List<String> preferences = currentUser
                .map(User::getPreferenceList)
                .orElse(List.of());
        return ArticleDetailDto.from(article, explanation, bookmarked,
                usefulnessEngine.evaluate(article, preferences),
                researchSummaryBuilder.build(article));
    }

    @Transactional(readOnly = true)
    public PageResponse<ArticleDto> search(String query, String category, String sort, int page, int size) {
        return search(query, category, null, null, sort, page, size);
    }

    @Transactional(readOnly = true)
    public PageResponse<ArticleDto> search(String query, String category, String region, String period, String sort, int page, int size) {
        if (query == null || query.isBlank()) {
            return list(category, region, period, sort, page, size);
        }
        Pageable pageable = pageable(page, size, sort);
        String trimmed = query.trim();
        Set<Long> bookmarked = bookmarkService.bookmarkedIds(Bookmark.ItemType.ARTICLE);

        String slug = category == null || category.isBlank() || "all".equalsIgnoreCase(category)
                ? null : requireCategory(category);
        Page<Article> result = articleRepository.findAll(
                ArticleSpecifications.filters(slug, normalizeRegion(region), periodStart(period), trimmed), pageable);
        return PageResponse.from(result, article -> toDto(article, bookmarked.contains(article.getId())));
    }

    private String normalizeRegion(String region) {
        if (region == null || region.isBlank() || "all".equalsIgnoreCase(region)) return null;
        String value = region.trim().toUpperCase(java.util.Locale.ROOT);
        if (!value.equals("INDIA") && !value.equals("GLOBAL")) throw new BadRequestException("region must be India or Global");
        return value;
    }

    private Instant periodStart(String period) {
        if (period == null || period.isBlank() || "all".equalsIgnoreCase(period)) return null;
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"));
        return switch (period.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "today" -> today.atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
            case "week" -> today.minusDays(6).atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
            default -> throw new BadRequestException("period must be today or week");
        };
    }

    public ArticleDto toDto(Article article, boolean bookmarked) {
        List<String> preferences = currentUserService.getCurrentUserOrNull()
                .map(User::getPreferenceList)
                .orElse(List.of());
        return ArticleDto.from(article, bookmarked,
                usefulnessEngine.evaluate(article, preferences));
    }

    @Transactional(readOnly = true)
    public Article requireArticle(Long id) {
        return articleRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Article " + id + " was not found"));
    }

    /** Upserts the reading history row so the profile shows the most recent read time. */
    @Transactional
    public void recordRead(Article article) {
        User user = currentUserService.getCurrentUserOrNull().orElse(null);
        if (user == null) {
            // Public discovery remains readable without creating an ownerless row.
            return;
        }
        ReadingHistory history = readingHistoryRepository
                .findByUserIdAndArticleId(user.getId(), article.getId())
                .orElseGet(ReadingHistory::new);
        history.setUser(user);
        history.setArticle(article);
        history.setReadAt(Instant.now());
        readingHistoryRepository.save(history);
    }

    private String requireCategory(String category) {
        return categoryRepository.findBySlug(category.trim().toLowerCase())
                .orElseThrow(() -> new ResourceNotFoundException("Category " + category + " was not found"))
                .getSlug();
    }

    private Pageable pageable(int page, int size, String sort) {
        if (page < 0) {
            throw new BadRequestException("page must be zero or greater");
        }
        int safeSize = size <= 0 ? 12 : Math.min(size, MAX_PAGE_SIZE);
        return PageRequest.of(page, safeSize, resolveSort(sort));
    }

    private Sort resolveSort(String sort) {
        String value = sort == null || sort.isBlank() ? "newest" : sort.trim().toLowerCase();
        // Null precedence is not supported by Spring Data JPA criteria sorting, so the
        // plain column sort is used. Articles without a feed date keep a null column and
        // are still returned; nothing fabricates a publication date for sorting.
        return switch (value) {
            case "newest", "latest" -> Sort.by(
                    Sort.Order.desc("publishedAt"),
                    Sort.Order.desc("id"));
            case "oldest" -> Sort.by(
                    Sort.Order.asc("publishedAt"),
                    Sort.Order.asc("id"));
            case "title" -> Sort.by(Sort.Order.asc("title"));
            default -> throw new BadRequestException("Unsupported sort value. Use newest, oldest or title.");
        };
    }
}
