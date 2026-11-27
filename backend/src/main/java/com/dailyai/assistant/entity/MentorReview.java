package com.dailyai.assistant.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "mentor_reviews",
        uniqueConstraints = @UniqueConstraint(name = "uk_mentor_review_user_date", columnNames = {"user_id", "review_date"}),
        indexes = @Index(name = "idx_mentor_review_user_date", columnList = "user_id, review_date"))
public class MentorReview {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "review_date", nullable = false) private LocalDate reviewDate;
    @Column(nullable = false, length = 16) private String status;
    @Column(name = "prompt_version", nullable = false, length = 32) private String promptVersion;
    @Column(name = "context_hash", nullable = false, length = 64) private String contextHash;
    @Column(name = "report_json", nullable = false, columnDefinition = "text") private String reportJson;
    @Column(name = "generated_at", nullable = false) private Instant generatedAt = Instant.now();

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public LocalDate getReviewDate() { return reviewDate; }
    public void setReviewDate(LocalDate reviewDate) { this.reviewDate = reviewDate; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getPromptVersion() { return promptVersion; }
    public void setPromptVersion(String promptVersion) { this.promptVersion = promptVersion; }
    public String getContextHash() { return contextHash; }
    public void setContextHash(String contextHash) { this.contextHash = contextHash; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String reportJson) { this.reportJson = reportJson; }
    public Instant getGeneratedAt() { return generatedAt; }
}
