package com.dailyai.assistant.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "user_topic_interests",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_topic_interest", columnNames = {"user_id", "topic"}),
        indexes = @Index(name = "idx_user_topic_interests_user", columnList = "user_id"))
public class UserTopicInterest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(nullable = false, length = 80)
    private String topic;
    @Column(nullable = false)
    private double score;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
