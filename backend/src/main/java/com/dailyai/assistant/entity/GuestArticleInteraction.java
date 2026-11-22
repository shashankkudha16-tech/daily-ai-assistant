package com.dailyai.assistant.entity;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "guest_article_interactions",
        uniqueConstraints = @UniqueConstraint(name = "uk_guest_article_interaction", columnNames = {"guest_id", "article_id"}),
        indexes = @Index(name = "idx_guest_interactions_guest_opened", columnList = "guest_id, opened_at"))
public class GuestArticleInteraction {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "guest_id", nullable = false, length = 36)
    private String guestId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;
    @Column(name = "open_count", nullable = false)
    private int openCount;
    @Column(name = "qualified_view_count", nullable = false)
    private int qualifiedViewCount;
    @Column(name = "opened_at")
    private Instant openedAt;
    @Column(name = "qualified_viewed_at")
    private Instant qualifiedViewedAt;
    @Column(name = "recommended_at")
    private Instant recommendedAt;

    public Long getId() { return id; }
    public String getGuestId() { return guestId; }
    public void setGuestId(String guestId) { this.guestId = guestId; }
    public Article getArticle() { return article; }
    public void setArticle(Article article) { this.article = article; }
    public int getOpenCount() { return openCount; }
    public void setOpenCount(int openCount) { this.openCount = openCount; }
    public int getQualifiedViewCount() { return qualifiedViewCount; }
    public void setQualifiedViewCount(int count) { this.qualifiedViewCount = count; }
    public Instant getOpenedAt() { return openedAt; }
    public void setOpenedAt(Instant openedAt) { this.openedAt = openedAt; }
    public Instant getQualifiedViewedAt() { return qualifiedViewedAt; }
    public void setQualifiedViewedAt(Instant qualifiedViewedAt) { this.qualifiedViewedAt = qualifiedViewedAt; }
    public Instant getRecommendedAt() { return recommendedAt; }
    public void setRecommendedAt(Instant recommendedAt) { this.recommendedAt = recommendedAt; }
}
