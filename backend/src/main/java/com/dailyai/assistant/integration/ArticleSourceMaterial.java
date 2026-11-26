package com.dailyai.assistant.integration;

import com.dailyai.assistant.entity.Article;

import java.util.Optional;

/** Selected provider input; completeness is independent from the kind of source. */
public record ArticleSourceMaterial(Type type, Completeness completeness, String text) {
    public enum Type { FULL_ARTICLE, RSS_CONTENT, EXCERPT, TITLE_ONLY }
    public enum Completeness { COMPLETE, PARTIAL, TRUNCATED, UNKNOWN }

    public record ExtractedContent(Type type, Completeness completeness, String text) {}

    public ArticleSourceMaterial {
        if (type == null) type = Type.TITLE_ONLY;
        if (completeness == null) completeness = Completeness.UNKNOWN;
        if (text == null) text = "";
    }

    public ArticleSourceMaterial(Type type, String text) {
        this(type, type == Type.FULL_ARTICLE ? Completeness.COMPLETE : Completeness.PARTIAL, text);
    }

    public static ArticleSourceMaterial fromFeed(Article article) {
        Optional<ArticleSourceMaterial> publisherContent = fromStoredPublisherContent(article);
        if (publisherContent.isPresent()) return publisherContent.get();
        if (article != null && article.isRssContentAiAllowed() && hasText(article.getRssContent())) {
            return new ArticleSourceMaterial(Type.RSS_CONTENT,
                    article.isRssContentTruncated() ? Completeness.TRUNCATED : Completeness.PARTIAL,
                    article.getRssContent().trim());
        }
        if (article != null && hasText(article.getDescription())) {
            return new ArticleSourceMaterial(Type.EXCERPT, Completeness.PARTIAL, article.getDescription().trim());
        }
        return new ArticleSourceMaterial(Type.TITLE_ONLY, Completeness.PARTIAL,
                article == null || article.getTitle() == null ? "" : article.getTitle().trim());
    }

    public static Optional<ArticleSourceMaterial> fromStoredPublisherContent(Article article) {
        if (article == null || !hasText(article.getPublisherSourceContent())
                || !hasText(article.getPublisherSourceType()) || !hasText(article.getPublisherSourceCompleteness())) {
            return Optional.empty();
        }
        try {
            Type type = Type.valueOf(article.getPublisherSourceType());
            Completeness completeness = Completeness.valueOf(article.getPublisherSourceCompleteness());
            if (type != Type.FULL_ARTICLE && type != Type.EXCERPT) return Optional.empty();
            return Optional.of(new ArticleSourceMaterial(type, completeness, article.getPublisherSourceContent().trim()));
        } catch (IllegalArgumentException invalidMetadata) {
            return Optional.empty();
        }
    }

    public static ArticleSourceMaterial choose(Article article, ExtractedContent extracted) {
        if (extracted != null && hasText(extracted.text())) {
            return new ArticleSourceMaterial(extracted.type(), extracted.completeness(), extracted.text().trim());
        }
        return fromFeed(article);
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
