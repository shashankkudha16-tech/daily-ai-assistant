package com.dailyai.assistant.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "personal_learning_plan_items", indexes =
        @Index(name = "idx_personal_plan_user_date", columnList = "user_id, plan_date, section"))
public class PersonalLearningPlanItem {
    public enum Section { LEARN, PRACTICE, STAY_UPDATED }
    public enum Status { PENDING, COMPLETED }
    public enum Priority { HIGH, MEDIUM, LOW }

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(name = "plan_date", nullable = false) private LocalDate planDate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private Section section;
    @Column(nullable = false, length = 250) private String title;
    @Column(nullable = false, columnDefinition = "text") private String description;
    @Column(nullable = false, columnDefinition = "text") private String rationale;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Priority priority;
    @Column(name = "estimated_minutes", nullable = false) private Integer estimatedMinutes;
    @Column(name = "related_skill", length = 200) private String relatedSkill;
    @Column(name = "due_date", nullable = false) private LocalDate dueDate;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 12) private Status status = Status.PENDING;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "source_article_id") private Article sourceArticle;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "source_topic_id") private LearningTopic sourceTopic;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "source_progress_entry_id") private UserProgressEntry sourceProgressEntry;
    @Column(name = "completed_at") private Instant completedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

    public Long getId() { return id; } public void setId(Long v) { id=v; }
    public User getUser() { return user; } public void setUser(User v) { user=v; }
    public LocalDate getPlanDate() { return planDate; } public void setPlanDate(LocalDate v) { planDate=v; }
    public Section getSection() { return section; } public void setSection(Section v) { section=v; }
    public String getTitle() { return title; } public void setTitle(String v) { title=v; }
    public String getDescription() { return description; } public void setDescription(String v) { description=v; }
    public String getRationale() { return rationale; } public void setRationale(String v) { rationale=v; }
    public Priority getPriority() { return priority; } public void setPriority(Priority v) { priority=v; }
    public Integer getEstimatedMinutes() { return estimatedMinutes; } public void setEstimatedMinutes(Integer v) { estimatedMinutes=v; }
    public String getRelatedSkill() { return relatedSkill; } public void setRelatedSkill(String v) { relatedSkill=v; }
    public LocalDate getDueDate() { return dueDate; } public void setDueDate(LocalDate v) { dueDate=v; }
    public Status getStatus() { return status; } public void setStatus(Status v) { status=v; }
    public Article getSourceArticle() { return sourceArticle; } public void setSourceArticle(Article v) { sourceArticle=v; }
    public LearningTopic getSourceTopic() { return sourceTopic; } public void setSourceTopic(LearningTopic v) { sourceTopic=v; }
    public UserProgressEntry getSourceProgressEntry() { return sourceProgressEntry; } public void setSourceProgressEntry(UserProgressEntry v) { sourceProgressEntry=v; }
    public Instant getCompletedAt() { return completedAt; } public void setCompletedAt(Instant v) { completedAt=v; }
    public Instant getCreatedAt() { return createdAt; } public void setCreatedAt(Instant v) { createdAt=v; }
}
