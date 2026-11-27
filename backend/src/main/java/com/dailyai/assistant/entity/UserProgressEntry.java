package com.dailyai.assistant.entity;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "user_progress_entries",
        uniqueConstraints = @UniqueConstraint(name = "uk_progress_entry_user_date", columnNames = {"user_id", "entry_date"}),
        indexes = @Index(name = "idx_progress_entry_user_date", columnList = "user_id, entry_date"))
public class UserProgressEntry {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "entry_date", nullable = false)
    private LocalDate entryDate;

    @Column(nullable = false, columnDefinition = "text") private String learned = "";
    @Column(nullable = false, columnDefinition = "text") private String practiced = "";
    @Column(nullable = false, columnDefinition = "text") private String built = "";
    @Column(nullable = false, columnDefinition = "text") private String difficulties = "";
    @Column(name = "tomorrow_goals", nullable = false, columnDefinition = "text") private String tomorrowGoals = "";
    @Column(name = "attachment_name", length = 255) private String attachmentName;
    @Column(name = "attachment_type", length = 120) private String attachmentType;
    @Column(name = "attachment_text", columnDefinition = "text") private String attachmentText;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public LocalDate getEntryDate() { return entryDate; }
    public void setEntryDate(LocalDate entryDate) { this.entryDate = entryDate; }
    public String getLearned() { return learned; }
    public void setLearned(String learned) { this.learned = learned; }
    public String getPracticed() { return practiced; }
    public void setPracticed(String practiced) { this.practiced = practiced; }
    public String getBuilt() { return built; }
    public void setBuilt(String built) { this.built = built; }
    public String getDifficulties() { return difficulties; }
    public void setDifficulties(String difficulties) { this.difficulties = difficulties; }
    public String getTomorrowGoals() { return tomorrowGoals; }
    public void setTomorrowGoals(String tomorrowGoals) { this.tomorrowGoals = tomorrowGoals; }
    public String getAttachmentName() { return attachmentName; }
    public void setAttachmentName(String attachmentName) { this.attachmentName = attachmentName; }
    public String getAttachmentType() { return attachmentType; }
    public void setAttachmentType(String attachmentType) { this.attachmentType = attachmentType; }
    public String getAttachmentText() { return attachmentText; }
    public void setAttachmentText(String attachmentText) { this.attachmentText = attachmentText; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
