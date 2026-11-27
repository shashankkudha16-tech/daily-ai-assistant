package com.dailyai.assistant.dto;

import java.util.List;

public record ProfileDto(
        Long id,
        String name,
        String email,
        int streakDays,
        long articlesRead,
        long bookmarks,
        int completedTopics,
        int totalTopics,
        int planProgressPercent,
        List<HistoryDto> history,
        List<String> preferences,
        String careerGoal,
        String targetRoles
) {
}
