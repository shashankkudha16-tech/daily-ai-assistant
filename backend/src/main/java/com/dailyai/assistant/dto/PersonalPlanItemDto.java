package com.dailyai.assistant.dto;

import com.dailyai.assistant.entity.PersonalLearningPlanItem;
import java.time.Instant;
import java.time.LocalDate;

public record PersonalPlanItemDto(Long id, LocalDate planDate, String section, String title, String description,
                                  String rationale, String priority, Integer estimatedMinutes, String relatedSkill,
                                  LocalDate dueDate, String status, String generationSource, Long sourceArticleId, String sourceArticleUrl,
                                  Long sourceTopicId, Long sourceProgressEntryId, Instant completedAt) {
    public static PersonalPlanItemDto from(PersonalLearningPlanItem item) {
        return new PersonalPlanItemDto(item.getId(), item.getPlanDate(), item.getSection().name(), item.getTitle(),
                item.getDescription(), item.getRationale(), item.getPriority().name(), item.getEstimatedMinutes(),
                item.getRelatedSkill(), item.getDueDate(), item.getStatus().name(),
                item.getRationale() != null && item.getRationale().startsWith("Recommended from your saved record:") ? "AI" : "BASIC",
                item.getSourceArticle() == null ? null : item.getSourceArticle().getId(),
                item.getSourceArticle() == null ? null : item.getSourceArticle().getUrl(),
                item.getSourceTopic() == null ? null : item.getSourceTopic().getId(),
                item.getSourceProgressEntry() == null ? null : item.getSourceProgressEntry().getId(), item.getCompletedAt());
    }
}
