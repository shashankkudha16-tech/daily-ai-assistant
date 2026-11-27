package com.dailyai.assistant.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record MentorReportDto(
        Long id, String status, String message, Instant generatedAt, String promptVersion, LocalDate contextFrom, LocalDate contextTo,
        String careerGoal, String targetRoles, List<Observation> completedTopics, List<Observation> skillActivity,
        List<Observation> challenges, List<Observation> learningGaps, List<Recommendation> recommendations
) {
    public record Observation(String label, String evidence, String date) {}
    public record Recommendation(String title, String action, String rationale, String relatedSkill, String priority,
                                 int estimatedMinutes, List<Observation> basis) {}
}
