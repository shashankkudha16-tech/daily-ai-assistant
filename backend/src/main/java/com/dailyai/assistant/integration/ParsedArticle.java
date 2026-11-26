package com.dailyai.assistant.integration;

import java.time.Instant;

public record ParsedArticle(
        String title,
        String url,
        String description,
        Instant publishedAt,
        String source,
        String author,
        String imageUrl,
        String rssContent,
        boolean rssContentTruncated,
        boolean rssContentAiAllowed
) {
    public ParsedArticle(String title, String url, String description, Instant publishedAt,
                         String source, String author, String imageUrl) {
        this(title, url, description, publishedAt, source, author, imageUrl, null, false, false);
    }

    public ParsedArticle(String title, String url, String description, Instant publishedAt,
                         String source, String author, String imageUrl, String rssContent) {
        this(title, url, description, publishedAt, source, author, imageUrl, rssContent, false, false);
    }
}
