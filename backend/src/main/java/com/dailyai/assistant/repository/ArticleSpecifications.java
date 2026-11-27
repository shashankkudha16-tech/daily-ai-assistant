package com.dailyai.assistant.repository;

import com.dailyai.assistant.entity.Article;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Adds only active filters, avoiding untyped nullable JPQL parameters in PostgreSQL. */
public final class ArticleSpecifications {
    private ArticleSpecifications() { }

    public static Specification<Article> filters(String category, String region, Instant from, String query) {
        return (root, criteria, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
            if (category != null) predicates.add(builder.equal(root.join("category").get("slug"), category));
            if (region != null) predicates.add(builder.equal(root.get("region"), region));
            if (from != null) predicates.add(builder.greaterThanOrEqualTo(root.get("publishedAt"), from));
            if (query != null && !query.isBlank()) {
                String pattern = "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
                predicates.add(builder.or(
                        builder.like(builder.lower(root.get("title")), pattern),
                        builder.like(builder.lower(builder.coalesce(root.get("description"), "")), pattern),
                        builder.like(builder.lower(builder.coalesce(root.get("source"), "")), pattern)));
            }
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }
}
