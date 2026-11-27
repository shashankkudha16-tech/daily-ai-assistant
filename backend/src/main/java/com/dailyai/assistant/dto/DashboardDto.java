package com.dailyai.assistant.dto;

import java.util.List;

public record DashboardDto(
        String greeting,
        String userName,
        String todayLabel,
        String briefing,
        List<ArticleDto> topUpdates,
        List<ArticleDto> latestNews,
        List<ToolDto> trendingTools,
        List<ArticleDto> recentModels,
        DailyTaskDto todayTask,
        LearningTopicDto continueLearning,
        int streakDays,
        Stats stats,
        String yesterdayProgress,
        String todayGoals,
        List<String> developingSkills
) {
    public record Stats(
            long articlesToday,
            long articlesTotal,
            int completedTopics,
            int totalTopics,
            int topicProgressPercent,
            int planProgressPercent,
            long bookmarks
    ) {
    }
}
