package com.dailyai.assistant.service;

import com.dailyai.assistant.dto.HistoryDto;
import com.dailyai.assistant.dto.ProfileDto;
import com.dailyai.assistant.dto.ProfilePreferencesRequest;
import com.dailyai.assistant.dto.ProfileCareerRequest;
import com.dailyai.assistant.dto.RoadmapDto;
import com.dailyai.assistant.entity.User;
import com.dailyai.assistant.repository.ReadingHistoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProfileService {

    private final CurrentUserService currentUserService;
    private final ReadingHistoryRepository readingHistoryRepository;
    private final LearningService learningService;
    private final BookmarkService bookmarkService;

    public ProfileService(CurrentUserService currentUserService,
                          ReadingHistoryRepository readingHistoryRepository,
                          LearningService learningService,
                          BookmarkService bookmarkService) {
        this.currentUserService = currentUserService;
        this.readingHistoryRepository = readingHistoryRepository;
        this.learningService = learningService;
        this.bookmarkService = bookmarkService;
    }

    @Transactional(readOnly = true)
    public ProfileDto profile() {
        User user = currentUserService.getCurrentUser();
        RoadmapDto roadmap = learningService.roadmap();
        List<HistoryDto> history = history(20);

        return new ProfileDto(
                user.getId(),
                user.getName(),
                user.getEmail(),
                learningService.streakDays(),
                readingHistoryRepository.countByUserId(user.getId()),
                bookmarkService.count(),
                roadmap.completedTopics(),
                roadmap.totalTopics(),
                learningService.planProgressPercent(),
                history,
                user.getPreferences().isBlank() ? List.of() : List.of(user.getPreferences().split(",")),
                user.getCareerGoal(), user.getTargetRoles()
        );
    }

    @Transactional
    public ProfileDto updateName(String name) {
        currentUserService.updateName(name);
        return profile();
    }

    @Transactional
    public ProfileDto updatePreferences(ProfilePreferencesRequest request) {
        var allowed = java.util.Set.of("AI_ML", "GENERATIVE_AI", "AI_CODING_AGENTS", "JAVA_BACKEND",
                "DEVELOPER_TOOLS", "AI_AUTOMATION", "CLOUD_AI_INFRASTRUCTURE", "AI_RESEARCH");
        var selected = request.preferences().stream().distinct().toList();
        if (selected.stream().anyMatch(value -> value == null || !allowed.contains(value))) {
            throw new com.dailyai.assistant.exception.BadRequestException("Unknown interest preference");
        }
        currentUserService.updatePreferences(String.join(",", selected));
        return profile();
    }

    @Transactional
    public ProfileDto updateCareerContext(ProfileCareerRequest request) {
        User user = currentUserService.getCurrentUser();
        user.setCareerGoal(request.careerGoal() == null ? "" : request.careerGoal().trim());
        user.setTargetRoles(request.targetRoles() == null ? "" : request.targetRoles().trim());
        return profile();
    }

    @Transactional(readOnly = true)
    public List<HistoryDto> history(int limit) {
        Long userId = currentUserService.getCurrentUserId();
        return readingHistoryRepository.findTop20ByUserIdOrderByReadAtDesc(userId).stream()
                .limit(Math.max(1, limit))
                .map(history -> new HistoryDto(
                        history.getId(),
                        history.getArticle().getId(),
                        history.getArticle().getTitle(),
                        history.getArticle().getSource(),
                        history.getArticle().getUrl(),
                        history.getReadAt()))
                .toList();
    }
}
