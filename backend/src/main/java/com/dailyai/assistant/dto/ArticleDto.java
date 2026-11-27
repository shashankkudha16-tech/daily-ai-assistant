package com.dailyai.assistant.dto;

import com.dailyai.assistant.entity.Article;

import java.time.Instant;

public record ArticleDto(
        Long id,
        String title,
        String url,
        String description,
        String source,
        String region,
        String author,
        Instant publishedAt,
        String category,
        String categoryName,
        String imageUrl,
        boolean bookmarked,
        ArticleUsefulnessDto usefulness
) {
    public static ArticleDto from(Article article, boolean bookmarked, ArticleUsefulnessDto usefulness) {
        return new ArticleDto(
                article.getId(),
                article.getTitle(),
                article.getUrl(),
                article.getDescription(),
                article.getSource(),
                article.getRegion(),
                displayAuthor(article.getAuthor()),
                article.getPublishedAt(),
                article.getCategory() == null ? null : article.getCategory().getSlug(),
                article.getCategory() == null ? null : article.getCategory().getName(),
                article.getImageUrl(),
                bookmarked,
                usefulness
        );
    }

    private static String displayAuthor(String author) {
        return author == null || author.isBlank() ? "Author unavailable" : author;
    }
}
