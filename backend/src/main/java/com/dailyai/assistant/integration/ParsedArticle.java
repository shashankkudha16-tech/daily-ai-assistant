package com.dailyai.assistant.integration;

import java.time.Instant;

public record ParsedArticle(
        String title,
        String url,
        String description,
        Instant publishedAt,
        String source,
        String author,
        String imageUrl
) {
}
