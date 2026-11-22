package com.dailyai.assistant.dto;

import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.ArticleExplanation;

import java.time.Instant;

public record ArticleDetailDto(
        Long id,
        String title,
        String url,
        String description,
        String source,
        String author,
        Instant publishedAt,
        Instant fetchedAt,
        String category,
        String categoryName,
        String imageUrl,
        boolean bookmarked,
        ExplanationDto explanation,
        ArticleUsefulnessDto usefulness,
        ArticleResearchSummaryDto researchSummary
) {
    public static ArticleDetailDto from(Article article, ArticleExplanation explanation, boolean bookmarked,
                                        ArticleUsefulnessDto usefulness, ArticleResearchSummaryDto researchSummary) {
        return new ArticleDetailDto(
                article.getId(),
                article.getTitle(),
                article.getUrl(),
                article.getDescription(),
                article.getSource(),
                article.getAuthor() == null || article.getAuthor().isBlank()
                        ? "Author unavailable" : article.getAuthor(),
                article.getPublishedAt(),
                article.getFetchedAt(),
                article.getCategory() == null ? null : article.getCategory().getSlug(),
                article.getCategory() == null ? null : article.getCategory().getName(),
                article.getImageUrl(),
                bookmarked,
                ExplanationDto.from(explanation),
                usefulness,
                researchSummary
        );
    }
}
