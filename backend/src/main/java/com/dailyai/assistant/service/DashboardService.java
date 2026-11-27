package com.dailyai.assistant.service;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.dto.ArticleDto;
import com.dailyai.assistant.dto.DashboardDto;
import com.dailyai.assistant.dto.RoadmapDto;
import com.dailyai.assistant.dto.ToolDto;
import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.Bookmark;
import com.dailyai.assistant.entity.User;
import com.dailyai.assistant.repository.ArticleRepository;
import com.dailyai.assistant.repository.UserProgressEntryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class DashboardService {

    private final ArticleService articleService;
    private final LearningService learningService;
    private final ToolService toolService;
    private final BookmarkService bookmarkService;
    private final CurrentUserService currentUserService;
    private final ArticleRepository articleRepository;
    private final AppProperties properties;
    private final UserProgressEntryRepository progressEntryRepository;

    public DashboardService(ArticleService articleService,
                            LearningService learningService,
                            ToolService toolService,
                            BookmarkService bookmarkService,
                            CurrentUserService currentUserService,
                            ArticleRepository articleRepository,
                            AppProperties properties,
                            UserProgressEntryRepository progressEntryRepository) {
        this.articleService = articleService;
        this.learningService = learningService;
        this.toolService = toolService;
        this.bookmarkService = bookmarkService;
        this.currentUserService = currentUserService;
        this.articleRepository = articleRepository;
        this.properties = properties;
        this.progressEntryRepository = progressEntryRepository;
    }

    @Transactional(readOnly = true)
    public DashboardDto dashboard() {
        User user = currentUserService.getCurrentUser();
        ZoneId zone = ZoneId.of(properties.getTimezone());
        Instant startOfToday = LocalDate.now(zone).atStartOfDay(zone).toInstant();

        RoadmapDto roadmap = learningService.roadmap();
        LocalDate yesterday = LocalDate.now(zone).minusDays(1);
        var yesterdayEntry = progressEntryRepository.findByUserIdAndEntryDate(user.getId(), yesterday).orElse(null);
        int planPercent = learningService.planProgressPercent();

        long collectedToday = articleRepository.countByFetchedAtAfter(startOfToday);
        long publishedThisWeek = articleRepository.countByPublishedAtAfter(Instant.now().minusSeconds(7L * 24 * 3600));

        List<ArticleDto> topUpdates = articleService.latest(5);
        List<ArticleDto> latestNews = java.util.stream.Stream.concat(
                        articleService.latestForRegion("INDIA", 6).stream(),
                        articleService.latestForRegion("GLOBAL", 6).stream())
                .sorted(java.util.Comparator.comparing(ArticleDto::publishedAt,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .limit(12).toList();
        List<ArticleDto> recentModels = recentModels();
        List<ToolDto> trendingTools = toolService.trending(6);

        DashboardDto.Stats stats = new DashboardDto.Stats(
                collectedToday,
                articleRepository.count(),
                roadmap.completedTopics(),
                roadmap.totalTopics(),
                roadmap.progressPercent(),
                planPercent,
                bookmarkService.count()
        );

        String briefing = buildBriefing(collectedToday, publishedThisWeek, roadmap.progressPercent());

        return new DashboardDto(
                greeting(zone),
                user.getName(),
                DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)
                        .format(LocalDate.now(zone)),
                briefing,
                topUpdates,
                latestNews,
                trendingTools,
                recentModels,
                learningService.todaysTask(),
                learningService.continueLearning(),
                learningService.streakDays(),
                stats,
                yesterdayEntry == null ? null : combineProgress(yesterdayEntry.getLearned(), yesterdayEntry.getPracticed(), yesterdayEntry.getBuilt()),
                yesterdayEntry == null || yesterdayEntry.getTomorrowGoals().isBlank() ? null : excerpt(yesterdayEntry.getTomorrowGoals()),
                roadmap.levels().stream().flatMap(level -> level.topics().stream())
                        .filter(topic -> "IN_PROGRESS".equals(topic.status())).map(topic -> topic.title()).limit(5).toList()
        );
    }

    private String combineProgress(String... fields) {
        String value = java.util.Arrays.stream(fields).filter(field -> field != null && !field.isBlank())
                .map(this::excerpt).collect(java.util.stream.Collectors.joining(" · "));
        return value.isBlank() ? null : value;
    }

    private String excerpt(String value) {
        String cleaned = value.trim().replaceAll("\\s+", " ");
        return cleaned.substring(0, Math.min(cleaned.length(), 320));
    }

    private List<ArticleDto> recentModels() {
        Set<Long> bookmarked = bookmarkService.bookmarkedIds(Bookmark.ItemType.ARTICLE);
        List<Article> models = articleRepository.findByCategorySlugInOrderByPublishedAtDesc(
                List.of("ai-models"), PageRequest.of(0, 5));
        if (models.isEmpty()) {
            models = articleRepository.findByCategorySlugInOrderByPublishedAtDesc(
                    List.of("generative-ai"), PageRequest.of(0, 5));
        }
        return models.stream()
                .map(article -> articleService.toDto(article, bookmarked.contains(article.getId())))
                .toList();
    }

    private String buildBriefing(long collectedToday, long publishedThisWeek, int roadmapPercent) {
        if (collectedToday == 0 && publishedThisWeek == 0) {
            return "No AI updates have been collected yet. Use refresh in Discover or wait for the next "
                    + "scheduled fetch (every 3 hours). Your learning roadmap is " + roadmapPercent + "% complete.";
        }
        return collectedToday + " new AI updates were collected today, and " + publishedThisWeek
                + " were published in the last 7 days. Your learning roadmap is " + roadmapPercent + "% complete.";
    }

    private String greeting(ZoneId zone) {
        int hour = LocalTime.now(zone).getHour();
        if (hour < 12) {
            return "Good morning";
        }
        if (hour < 17) {
            return "Good afternoon";
        }
        return "Good evening";
    }
}
