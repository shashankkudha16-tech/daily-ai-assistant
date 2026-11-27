package com.dailyai.assistant.service;

import com.dailyai.assistant.dto.ArticleDto;
import com.dailyai.assistant.dto.PageResponse;
import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.ArticleExplanation;
import com.dailyai.assistant.entity.Bookmark;
import com.dailyai.assistant.entity.Category;
import com.dailyai.assistant.entity.User;
import com.dailyai.assistant.exception.ResourceNotFoundException;
import com.dailyai.assistant.repository.ArticleRepository;
import com.dailyai.assistant.repository.CategoryRepository;
import com.dailyai.assistant.repository.ReadingHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ArticleServiceTest {

    @Mock
    private ArticleRepository articleRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private BookmarkService bookmarkService;
    @Mock
    private ReadingHistoryRepository readingHistoryRepository;
    @Mock
    private ExplanationService explanationService;
    @Mock
    private CurrentUserService currentUserService;

    private ArticleService service;

    @BeforeEach
    void setUp() {
        service = new ArticleService(articleRepository, categoryRepository, bookmarkService,
                readingHistoryRepository, explanationService, currentUserService, new ArticleUsefulnessEngine(),
                new ResearchSummaryBuilder(), new ArticleAiGenerationGuard());
        org.mockito.Mockito.lenient().when(currentUserService.getCurrentUser())
                .thenReturn(new com.dailyai.assistant.entity.User("Test", "test@example.com"));
    }

    @Test
    void listMarksBookmarkedArticlesAndSortsNewest() {
        Article article = article(1L, "News", "generative-ai", Instant.parse("2026-11-16T00:00:00Z"));
        Page<Article> page = new PageImpl<>(List.of(article));
        when(articleRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class))).thenReturn(page);
        when(bookmarkService.bookmarkedIds(Bookmark.ItemType.ARTICLE)).thenReturn(Set.of(1L));

        PageResponse<ArticleDto> response = service.list("all", "newest", 0, 12);

        assertThat(response.content()).hasSize(1);
        assertThat(response.content().get(0).bookmarked()).isTrue();
        assertThat(response.totalElements()).isEqualTo(1);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(articleRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class), captor.capture());
        assertThat(captor.getValue().getSort().getOrderFor("publishedAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void filtersNewsBySourceRegionAndRejectsUnknownRegions() {
        when(articleRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class)))
                .thenReturn(Page.empty());
        service.list("all", "india", "today", "newest", 0, 12);
        verify(articleRepository).findAll(any(org.springframework.data.jpa.domain.Specification.class), any(Pageable.class));
        assertThatThrownBy(() -> service.list("all", "mars", "today", "newest", 0, 12))
                .isInstanceOf(com.dailyai.assistant.exception.BadRequestException.class);
    }

    @Test
    void unknownCategoryReturnsNotFound() {
        when(categoryRepository.findBySlug("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list("unknown", "newest", 0, 12))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void unsupportedSortValueIsRejected() {
        assertThatThrownBy(() -> service.list(null, "random", 0, 12))
                .isInstanceOf(com.dailyai.assistant.exception.BadRequestException.class);
        verify(articleRepository, never()).findAll(any(Pageable.class));
    }

    @Test
    void authenticatedDetailCanStartNewAiGeneration() {
        Article article = article(1L, "News", "generative-ai", Instant.parse("2026-11-16T00:00:00Z"));
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article));
        when(bookmarkService.isBookmarked(Bookmark.ItemType.ARTICLE, 1L)).thenReturn(false);
        when(currentUserService.getCurrentUserOrNull())
                .thenReturn(Optional.of(new User("Test", "test@example.com")));
        when(explanationService.getOrCreate(article, true)).thenReturn(basicExplanation(article));

        service.get(1L);

        verify(explanationService).getOrCreate(article, true);
    }

    @Test
    void anonymousDetailNeverStartsNewAiGeneration() {
        Article article = article(1L, "News", "generative-ai", Instant.parse("2026-11-16T00:00:00Z"));
        when(articleRepository.findById(1L)).thenReturn(Optional.of(article));
        when(bookmarkService.isBookmarked(Bookmark.ItemType.ARTICLE, 1L)).thenReturn(false);
        when(currentUserService.getCurrentUserOrNull()).thenReturn(Optional.empty());
        when(explanationService.getOrCreate(article, false)).thenReturn(basicExplanation(article));

        service.get(1L);

        verify(explanationService).getOrCreate(article, false);
    }

    @Test
    void detailThrowsWhenArticleDoesNotExist() {
        when(articleRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    private ArticleExplanation basicExplanation(Article article) {
        ArticleExplanation explanation = new ArticleExplanation();
        explanation.setArticle(article);
        explanation.setGeneratedBy(ArticleExplanation.GeneratedBy.BASIC);
        explanation.setGeneratedAt(Instant.now());
        explanation.setWhatHappened("Offline text.");
        return explanation;
    }

    private Article article(Long id, String title, String slug, Instant publishedAt) {
        Category category = new Category("Generative AI", slug, null);
        category.setId(1L);
        Article article = new Article();
        article.setId(id);
        article.setTitle(title);
        article.setUrl("https://example.com/" + id);
        article.setPublishedAt(publishedAt);
        article.setCategory(category);
        return article;
    }
}
