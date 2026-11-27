package com.dailyai.assistant.service;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.entity.*;
import com.dailyai.assistant.repository.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersonalLearningPlanServiceTest {
    private final PersonalLearningPlanRepository plans = mock(PersonalLearningPlanRepository.class);
    private final LearningTopicRepository topics = mock(LearningTopicRepository.class);
    private final LearningProgressRepository progress = mock(LearningProgressRepository.class);
    private final UserProgressEntryRepository journal = mock(UserProgressEntryRepository.class);
    private final ArticleRepository articles = mock(ArticleRepository.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final AppProperties properties = new AppProperties();
    private final LearningPlanAiClient aiClient = mock(LearningPlanAiClient.class);

    @Test
    void persistsGroundedAiPracticeTaskWithEvidenceAndDueDate() {
        properties.getAi().setEnabled(true);
        properties.getAi().setApiKey("test-key");
        User user = learner();
        when(currentUser.getCurrentUser()).thenReturn(user);
        when(plans.existsByUserIdAndPlanDate(eq(71L), any())).thenReturn(false);
        when(progress.findByUserId(71L)).thenReturn(List.of());
        when(topics.findAllByOrderByLevelAscOrderIndexAsc()).thenReturn(List.of());
        UserProgressEntry difficulty = new UserProgressEntry();
        difficulty.setUser(user); difficulty.setEntryDate(java.time.LocalDate.now());
        difficulty.setDifficulties("HashMap internals remain difficult");
        when(journal.findTop20ByUserIdOrderByEntryDateDesc(71L)).thenReturn(List.of(difficulty));
        when(articles.findByCategorySlugInOrderByPublishedAtDesc(anyList(), any())).thenReturn(List.of());
        when(aiClient.generate(anyList(), any())).thenAnswer(call -> {
            List<LearningPlanAiClient.Evidence> evidence = call.getArgument(0);
            String id = evidence.stream().filter(item -> item.kind().equals("CHALLENGE")).findFirst().orElseThrow().id();
            return Optional.of(new LearningPlanAiClient.Result(List.of(new LearningPlanAiClient.Task(
                    "PRACTICE", "PRACTICE_EXERCISE", "PRACTICE_RECORDED_DIFFICULTY",
                    "HashMap internals remain difficult", "HIGH", 25, id)), 400, 90));
        });
        AtomicReference<List<PersonalLearningPlanItem>> saved = new AtomicReference<>(List.of());
        when(plans.saveAll(anyIterable())).thenAnswer(call -> {
            List<PersonalLearningPlanItem> values = new ArrayList<>();
            ((Iterable<PersonalLearningPlanItem>) call.getArgument(0)).forEach(values::add);
            saved.set(values); return values;
        });
        when(plans.findByUserIdAndPlanDateOrderBySectionAscIdAsc(eq(71L), any())).thenAnswer(call -> saved.get());

        var result = new PersonalLearningPlanService(plans, topics, progress, journal, articles, currentUser, properties, aiClient)
                .createOrLoadToday();

        verify(aiClient).generate(anyList(), any());
        assertThat(result).hasSize(1);
        assertThat(result.get(0).section()).isEqualTo("PRACTICE");
        assertThat(result.get(0).generationSource()).isEqualTo("AI");
        assertThat(result.get(0).status()).isEqualTo("PENDING");
        assertThat(result.get(0).dueDate()).isEqualTo(result.get(0).planDate());
        assertThat(result.get(0).rationale()).contains("HashMap internals remain difficult");
        assertThat(saved.get().get(0).getSourceProgressEntry()).isSameAs(difficulty);
        verify(aiClient).generate(anyList(), any());
    }

    @Test
    void existingDailyPlanPreventsRegenerationAndPreservesCompletedItem() {
        User user = learner();
        when(currentUser.getCurrentUser()).thenReturn(user);
        PersonalLearningPlanItem completed = new PersonalLearningPlanItem();
        completed.setId(88L); completed.setUser(user); completed.setPlanDate(java.time.LocalDate.now());
        completed.setSection(PersonalLearningPlanItem.Section.LEARN); completed.setTitle("Learn Spring REST");
        completed.setDescription("Review saved roadmap evidence."); completed.setRationale("Already completed earlier today.");
        completed.setPriority(PersonalLearningPlanItem.Priority.MEDIUM); completed.setEstimatedMinutes(20);
        completed.setDueDate(java.time.LocalDate.now()); completed.setStatus(PersonalLearningPlanItem.Status.COMPLETED);
        when(plans.existsByUserIdAndPlanDate(eq(71L), any())).thenReturn(true);
        when(plans.findByUserIdAndPlanDateOrderBySectionAscIdAsc(eq(71L), any())).thenReturn(List.of(completed));

        var result = new PersonalLearningPlanService(plans, topics, progress, journal, articles, currentUser, properties, aiClient)
                .createOrLoadToday();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).status()).isEqualTo("COMPLETED");
        assertThat(result.get(0).generationSource()).isEqualTo("BASIC");
        verifyNoInteractions(aiClient, topics, progress, journal, articles);
        verify(plans, never()).saveAll(anyIterable());
    }

    @Test
    void allRejectedAiTasksUseDeterministicFallbackAndDoNotInventNews() {
        properties.getAi().setEnabled(true);
        properties.getAi().setApiKey("test-key");
        User user = learner();
        when(currentUser.getCurrentUser()).thenReturn(user);
        when(plans.existsByUserIdAndPlanDate(eq(71L), any())).thenReturn(false);
        when(progress.findByUserId(71L)).thenReturn(List.of());
        when(topics.findAllByOrderByLevelAscOrderIndexAsc()).thenReturn(List.of(topic(2L, "Java REST API", "Spring backend services")));
        UserProgressEntry difficulty = new UserProgressEntry();
        difficulty.setUser(user); difficulty.setEntryDate(java.time.LocalDate.now());
        difficulty.setDifficulties("API testing remains difficult");
        when(journal.findTop20ByUserIdOrderByEntryDateDesc(71L)).thenReturn(List.of(difficulty));
        when(articles.findByCategorySlugInOrderByPublishedAtDesc(anyList(), any())).thenReturn(List.of());
        when(aiClient.generate(anyList(), any())).thenReturn(Optional.empty());
        AtomicReference<List<PersonalLearningPlanItem>> saved = new AtomicReference<>(List.of());
        when(plans.saveAll(anyIterable())).thenAnswer(call -> {
            List<PersonalLearningPlanItem> values = new ArrayList<>();
            ((Iterable<PersonalLearningPlanItem>) call.getArgument(0)).forEach(values::add);
            saved.set(values); return values;
        });
        when(plans.findByUserIdAndPlanDateOrderBySectionAscIdAsc(eq(71L), any())).thenAnswer(call -> saved.get());

        var result = new PersonalLearningPlanService(plans, topics, progress, journal, articles, currentUser, properties, aiClient)
                .createOrLoadToday();

        assertThat(result).extracting(item -> item.generationSource()).containsOnly("BASIC");
        assertThat(result).extracting(item -> item.section()).contains("LEARN", "PRACTICE");
        assertThat(result).extracting(item -> item.section()).doesNotContain("STAY_UPDATED");
    }

    @Test
    void usesCareerAndRoadmapForLearnAndMappedInterestAndEvidenceForStayUpdated() {
        User user = learner();
        when(currentUser.getCurrentUser()).thenReturn(user);
        when(plans.existsByUserIdAndPlanDate(eq(71L), any())).thenReturn(false);
        when(progress.findByUserId(71L)).thenReturn(List.of());
        LearningTopic linear = topic(1L, "Linear Algebra", "Matrix basics");
        LearningTopic javaTopic = topic(2L, "Java REST API", "Build backend services with Java and Spring");
        LearningTopic sql = topic(3L, "SQL Window Functions", "Ranking queries");
        when(topics.findAllByOrderByLevelAscOrderIndexAsc()).thenReturn(List.of(linear, javaTopic, sql));
        UserProgressEntry entry = new UserProgressEntry();
        entry.setUser(user); entry.setEntryDate(java.time.LocalDate.now());
        entry.setDifficulties("SQL window functions");
        when(journal.findTop20ByUserIdOrderByEntryDateDesc(71L)).thenReturn(List.of(entry));
        Category category = new Category("Generative AI", "generative-ai", "Models");
        Article article = new Article(); article.setId(91L); article.setTitle("Recent model release");
        article.setUrl("https://news.example/model"); article.setDescription("A relevant model update.");
        article.setCategory(category); article.setPublishedAt(Instant.now().minusSeconds(3600));
        when(articles.findByCategorySlugInOrderByPublishedAtDesc(anyList(), any())).thenReturn(List.of(article));
        AtomicReference<List<PersonalLearningPlanItem>> saved = new AtomicReference<>(List.of());
        when(plans.saveAll(anyIterable())).thenAnswer(call -> {
            List<PersonalLearningPlanItem> values = new ArrayList<>();
            ((Iterable<PersonalLearningPlanItem>) call.getArgument(0)).forEach(values::add);
            saved.set(values);
            return values;
        });
        when(plans.findByUserIdAndPlanDateOrderBySectionAscIdAsc(eq(71L), any()))
                .thenAnswer(call -> saved.get());

        var result = new PersonalLearningPlanService(plans, topics, progress, journal, articles, currentUser, properties)
                .createOrLoadToday();

        assertThat(result).extracting(item -> item.section()).contains("LEARN", "PRACTICE", "STAY_UPDATED");
        var learn = result.stream().filter(item -> item.section().equals("LEARN")).findFirst().orElseThrow();
        assertThat(learn.title()).contains("Java REST API");
        assertThat(learn.rationale()).contains("career goal").contains("Java Backend Developer");
        var practice = result.stream().filter(item -> item.section().equals("PRACTICE")).findFirst().orElseThrow();
        assertThat(practice.relatedSkill()).isEqualTo("SQL Window Functions");
        var update = result.stream().filter(item -> item.section().equals("STAY_UPDATED")).findFirst().orElseThrow();
        assertThat(update.sourceArticleId()).isEqualTo(91L);
        assertThat(update.sourceArticleUrl()).isEqualTo("https://news.example/model");
        assertThat(update.rationale()).contains("GENERATIVE_AI");
        verify(articles).findByCategorySlugInOrderByPublishedAtDesc(argThat(slugs ->
                slugs.contains("generative-ai") && slugs.contains("ai-models") && !slugs.contains("GENERATIVE_AI")), any());
    }

    @Test
    void omitsStayUpdatedWhenNoMatchingRecentArticleExists() {
        User user = learner();
        when(currentUser.getCurrentUser()).thenReturn(user);
        when(plans.existsByUserIdAndPlanDate(eq(71L), any())).thenReturn(false);
        when(progress.findByUserId(71L)).thenReturn(List.of());
        when(topics.findAllByOrderByLevelAscOrderIndexAsc()).thenReturn(List.of());
        when(journal.findTop20ByUserIdOrderByEntryDateDesc(71L)).thenReturn(List.of());
        when(articles.findByCategorySlugInOrderByPublishedAtDesc(anyList(), any())).thenReturn(List.of());
        when(plans.saveAll(anyIterable())).thenAnswer(call -> call.getArgument(0));
        when(plans.findByUserIdAndPlanDateOrderBySectionAscIdAsc(eq(71L), any())).thenReturn(List.of());
        var result = new PersonalLearningPlanService(plans, topics, progress, journal, articles, currentUser, properties)
                .createOrLoadToday();
        assertThat(result).isEmpty();
    }

    private User learner() {
        User user = new User("Plan Test", "plan@test.example"); user.setId(71L);
        user.setCareerGoal("Java Backend Developer"); user.setTargetRoles("Java Developer");
        user.setPreferences("GENERATIVE_AI"); return user;
    }
    private LearningTopic topic(long id, String title, String explanation) {
        LearningTopic topic = new LearningTopic(); topic.setId(id); topic.setTitle(title); topic.setExplanation(explanation); return topic;
    }
}
