package com.dailyai.assistant.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "guest_topic_interests",
        uniqueConstraints = @UniqueConstraint(name = "uk_guest_topic_interest", columnNames = {"guest_id", "topic"}),
        indexes = @Index(name = "idx_guest_topic_interests_guest", columnList = "guest_id"))
public class GuestTopicInterest {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "guest_id", nullable = false, length = 36)
    private String guestId;
    @Column(nullable = false, length = 80)
    private String topic;
    @Column(nullable = false)
    private double score;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Long getId() { return id; }
    public String getGuestId() { return guestId; }
    public void setGuestId(String guestId) { this.guestId = guestId; }
    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }
    public double getScore() { return score; }
    public void setScore(double score) { this.score = score; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
