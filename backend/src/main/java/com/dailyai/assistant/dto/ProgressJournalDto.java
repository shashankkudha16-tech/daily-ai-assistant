package com.dailyai.assistant.dto;

import com.dailyai.assistant.entity.UserProgressEntry;

import java.time.Instant;
import java.time.LocalDate;

public record ProgressJournalDto(
        Long id, LocalDate date, String learned, String practiced, String built, String difficulties,
        String tomorrowGoals, String attachmentName, String attachmentType, String attachmentText,
        Instant createdAt, Instant updatedAt
) {
    public static ProgressJournalDto from(UserProgressEntry entry) {
        return new ProgressJournalDto(entry.getId(), entry.getEntryDate(), entry.getLearned(), entry.getPracticed(),
                entry.getBuilt(), entry.getDifficulties(), entry.getTomorrowGoals(), entry.getAttachmentName(),
                entry.getAttachmentType(), entry.getAttachmentText(), entry.getCreatedAt(), entry.getUpdatedAt());
    }
}
