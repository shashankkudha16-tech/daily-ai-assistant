package com.dailyai.assistant.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "articles", indexes = {
        @Index(name = "idx_articles_published_at", columnList = "published_at"),
        @Index(name = "idx_articles_fetched_at", columnList = "fetched_at"),
        @Index(name = "idx_articles_category", columnList = "category_id")
})
public class Article {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 500)
    private String title;

    /** Original article URL, used as the de-duplication key. */
    @Column(nullable = false, unique = true, length = 1000)
    private String url;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(length = 200)
    private String source;

    @Column(length = 300)
    private String author;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt = Instant.now();

    @Column(name = "image_url", length = 1000)
    private String imageUrl;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private Category category;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public void setFetchedAt(Instant fetchedAt) {
        this.fetchedAt = fetchedAt;
    }

    public String getImageUrl() {
        return imageUrl;
    }

    public void setImageUrl(String imageUrl) {
        this.imageUrl = imageUrl;
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }
}
