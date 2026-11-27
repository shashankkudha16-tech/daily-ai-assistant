package com.dailyai.assistant.service;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.dto.DailyPlanDto;
import com.dailyai.assistant.dto.MentorReportDto;
import com.dailyai.assistant.dto.RoadmapDto;
import com.dailyai.assistant.entity.MentorReview;
import com.dailyai.assistant.entity.User;
import com.dailyai.assistant.entity.UserProgressEntry;
import com.dailyai.assistant.repository.MentorReviewRepository;
import com.dailyai.assistant.repository.PersonalLearningPlanRepository;
import com.dailyai.assistant.repository.UserProgressEntryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PersonalMentorServiceTest {
    private final CurrentUserService users = mock(CurrentUserService.class);
    private final UserProgressEntryRepository journal = mock(UserProgressEntryRepository.class);
    private final MentorReviewRepository reports = mock(MentorReviewRepository.class);
    private final PersonalLearningPlanRepository personalPlans = mock(PersonalLearningPlanRepository.class);
    private final LearningService learning = mock(LearningService.class);
    private final MentorAiClient ai = mock(MentorAiClient.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final PersonalMentorService service = new PersonalMentorService(users, journal, reports, learning, ai,
            new AppProperties(), mapper, personalPlans);

    @Test
    void withoutRecordedHistoryReturnsHonestBasicReviewWithoutCallingProvider() {
        User user = user();
        when(users.getCurrentUser()).thenReturn(user);
        when(journal.findTop20ByUserIdOrderByEntryDateDesc(9L)).thenReturn(List.of());
        when(learning.roadmap()).thenReturn(new RoadmapDto(List.of(), 0, 0, 0));
        when(learning.dailyPlan()).thenReturn(new DailyPlanDto(List.of(), 0, 0, 0));
        when(reports.findByUserIdAndReviewDate(eq(9L), any(LocalDate.class))).thenReturn(Optional.empty());
        when(reports.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(reports.save(any())).thenAnswer(call -> call.getArgument(0));

        MentorReportDto result = service.generate();

        assertThat(result.status()).isEqualTo("BASIC");
        assertThat(result.message()).contains("not enough recorded learning history");
        assertThat(result.recommendations()).isEmpty();
        verifyNoInteractions(ai);
        verify(reports).findByUserIdAndReviewDate(eq(9L), any(LocalDate.class));
    }

    @Test
    void recommendationsAreBoundToKnownUserEvidenceAndCachedPerUserAndDate() {
        User user = user();
        LocalDate day = LocalDate.of(2026, 11, 25);
        UserProgressEntry entry = new UserProgressEntry();
        entry.setUser(user);
        entry.setEntryDate(day);
        entry.setLearned("Practiced Java collections from a tutorial.");
        when(users.getCurrentUser()).thenReturn(user);
        when(journal.findTop20ByUserIdOrderByEntryDateDesc(9L)).thenReturn(List.of(entry));
        when(learning.roadmap()).thenReturn(new RoadmapDto(List.of(), 0, 0, 0));
        when(learning.dailyPlan()).thenReturn(new DailyPlanDto(List.of(), 0, 0, 0));
        when(reports.findByUserIdAndReviewDate(eq(9L), any(LocalDate.class))).thenReturn(Optional.empty());
        when(reports.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(reports.save(any())).thenAnswer(call -> call.getArgument(0));
        when(ai.recommend(anyList(), any(LocalDate.class))).thenAnswer(call -> {
            @SuppressWarnings("unchecked") List<MentorAiClient.Evidence> evidence = call.getArgument(0);
            assertThat(evidence).extracting(MentorAiClient.Evidence::id).contains("journal-2026-11-25-learned");
            return Optional.of(new MentorAiClient.Result(List.of(new MentorAiClient.SuggestedAction(
                    "Practice collections", "Write a small map/set exercise.", "Practiced Java collections from a tutorial.", "", "MEDIUM", 20,
                    List.of("journal-2026-11-25-learned"))), 100, 30));
        });

        MentorReportDto result = service.generate();

        assertThat(result.status()).isEqualTo("AI");
        assertThat(result.recommendations()).hasSize(1);
        assertThat(result.recommendations().get(0).basis()).singleElement()
                .extracting(MentorReportDto.Observation::evidence)
                .isEqualTo("Practiced Java collections from a tutorial.");
        verify(ai).recommend(anyList(), eq(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))));
    }

    @Test
    void providerFailureWithHistoryPersistsAnExplicitBasicReport() {
        User user = user();
        UserProgressEntry entry = new UserProgressEntry();
        entry.setUser(user); entry.setEntryDate(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
        entry.setDifficulties("SQL window functions");
        when(users.getCurrentUser()).thenReturn(user);
        when(journal.findTop20ByUserIdOrderByEntryDateDesc(9L)).thenReturn(List.of(entry));
        when(learning.roadmap()).thenReturn(new RoadmapDto(List.of(), 0, 0, 0));
        when(learning.dailyPlan()).thenReturn(new DailyPlanDto(List.of(), 0, 0, 0));
        when(reports.findByUserIdAndReviewDate(eq(9L), any(LocalDate.class))).thenReturn(Optional.empty());
        when(reports.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(reports.save(any())).thenAnswer(call -> call.getArgument(0));
        when(ai.recommend(anyList(), any(LocalDate.class))).thenReturn(Optional.empty());

        MentorReportDto result = service.generate();

        assertThat(result.status()).isEqualTo("BASIC");
        assertThat(result.message()).contains("unavailable");
        assertThat(result.recommendations()).isEmpty();
        verify(reports).save(argThat(saved -> ((MentorReview) saved).getStatus().equals("BASIC")));
    }

    @Test
    void emptySurvivingRecommendationSetRemainsBasic() {
        User user = user();
        UserProgressEntry entry = new UserProgressEntry();
        entry.setUser(user); entry.setEntryDate(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
        entry.setDifficulties("HashMap internals");
        when(users.getCurrentUser()).thenReturn(user);
        when(journal.findTop20ByUserIdOrderByEntryDateDesc(9L)).thenReturn(List.of(entry));
        when(learning.roadmap()).thenReturn(new RoadmapDto(List.of(), 0, 0, 0));
        when(learning.dailyPlan()).thenReturn(new DailyPlanDto(List.of(), 0, 0, 0));
        when(reports.findByUserIdAndReviewDate(eq(9L), any(LocalDate.class))).thenReturn(Optional.empty());
        when(reports.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        when(reports.save(any())).thenAnswer(call -> call.getArgument(0));
        when(ai.recommend(anyList(), any(LocalDate.class))).thenReturn(Optional.of(new MentorAiClient.Result(List.of(), 100, 20)));

        MentorReportDto result = service.generate();

        assertThat(result.status()).isEqualTo("BASIC");
        assertThat(result.recommendations()).isEmpty();
        assertThat(result.message()).contains("No AI recommendation passed the evidence checks");
    }

    @Test
    void enabledAiRegeneratesSameDayBasicCacheWithoutChangingSavedEvidence() {
        AppProperties runtimeProperties = new AppProperties();
        runtimeProperties.getAi().setEnabled(false);
        User user = user();
        UserProgressEntry entry = new UserProgressEntry();
        entry.setUser(user);
        entry.setEntryDate(LocalDate.now(java.time.ZoneId.of("Asia/Kolkata")));
        entry.setDifficulties("HashMap internals");
        when(users.getCurrentUser()).thenReturn(user);
        when(journal.findTop20ByUserIdOrderByEntryDateDesc(9L)).thenReturn(List.of(entry));
        when(learning.roadmap()).thenReturn(new RoadmapDto(List.of(), 0, 0, 0));
        when(learning.dailyPlan()).thenReturn(new DailyPlanDto(List.of(), 0, 0, 0));
        AtomicReference<MentorReview> stored = new AtomicReference<>();
        when(reports.findByUserIdAndReviewDate(eq(9L), any(LocalDate.class)))
                .thenAnswer(call -> Optional.ofNullable(stored.get()));
        when(reports.saveAndFlush(any())).thenAnswer(call -> {
            MentorReview review = call.getArgument(0);
            stored.set(review);
            return review;
        });
        when(reports.save(any())).thenAnswer(call -> {
            MentorReview review = call.getArgument(0);
            stored.set(review);
            return review;
        });
        PersonalMentorService runtimeService = new PersonalMentorService(users, journal, reports, learning, ai,
                runtimeProperties, mapper, personalPlans);

        MentorReportDto cachedBasic = runtimeService.generate();
        assertThat(cachedBasic.status()).isEqualTo("BASIC");
        clearInvocations(ai);

        runtimeProperties.getAi().setEnabled(true);
        runtimeProperties.getAi().setApiKey("test-key");
        when(ai.recommend(anyList(), any(LocalDate.class))).thenReturn(Optional.of(new MentorAiClient.Result(
                List.of(new MentorAiClient.SuggestedAction("Practice HashMap internals", "Build a small collision example.",
                        "HashMap internals", "", "MEDIUM", 20, List.of("journal-" + entry.getEntryDate() + "-difficulty"))), 100, 40)));

        MentorReportDto generated = runtimeService.generate();

        assertThat(generated.status()).isEqualTo("AI");
        assertThat(generated.recommendations()).hasSize(1);
        verify(ai).recommend(argThat(evidence -> evidence.stream().anyMatch(item -> item.text().contains("HashMap internals"))),
                any(LocalDate.class));
    }

    @Test
    void readsCurrentReportOnlyForAuthenticatedUser() {
        when(users.getCurrentUserId()).thenReturn(9L);
        when(reports.findByUserIdAndReviewDate(eq(9L), any(LocalDate.class))).thenReturn(Optional.empty());
        assertThat(service.current()).isNull();
        verify(reports).findByUserIdAndReviewDate(eq(9L), any(LocalDate.class));
    }

    private User user() {
        User user = new User("Learner", "learner@example.test");
        user.setId(9L);
        user.setCareerGoal("Prepare for backend roles");
        user.setTargetRoles("Java developer");
        return user;
    }
}
