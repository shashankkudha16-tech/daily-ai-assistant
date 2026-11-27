package com.dailyai.assistant.repository;

import com.dailyai.assistant.entity.Article;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ArticleRepository extends JpaRepository<Article, Long>, JpaSpecificationExecutor<Article> {

    Optional<Article> findByUrl(String url);

    boolean existsByUrl(String url);

    Page<Article> findByCategorySlug(String slug, Pageable pageable);

    Page<Article> findByPublishedAtAfter(Instant from, Pageable pageable);

    List<Article> findByCategorySlugInOrderByPublishedAtDesc(List<String> slugs, Pageable pageable);

    List<Article> findByPublishedAtIsNotNullOrderByPublishedAtDesc(Pageable pageable);

    List<Article> findByRegionAndPublishedAtIsNotNullOrderByPublishedAtDesc(String region, Pageable pageable);

    long countByPublishedAtAfter(Instant from);

    long countByFetchedAtAfter(Instant from);

    long countByCategorySlug(String slug);

}
