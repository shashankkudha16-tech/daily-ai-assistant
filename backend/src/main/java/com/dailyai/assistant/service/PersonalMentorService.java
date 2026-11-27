package com.dailyai.assistant.service;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.dto.DailyPlanDto;
import com.dailyai.assistant.dto.LearningTopicDto;
import com.dailyai.assistant.dto.MentorReportDto;
import com.dailyai.assistant.dto.RoadmapDto;
import com.dailyai.assistant.entity.MentorReview;
import com.dailyai.assistant.repository.MentorReviewRepository;
import com.dailyai.assistant.repository.PersonalLearningPlanRepository;
import com.dailyai.assistant.repository.UserProgressEntryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class PersonalMentorService {
    private static final int MAX_CONTEXT_CHARS = 12_000;
    private static final Map<Long, ReentrantLock> USER_LOCKS = new ConcurrentHashMap<>();

    private final CurrentUserService currentUserService;
    private final UserProgressEntryRepository progressRepository;
    private final MentorReviewRepository reviewRepository;
    private final LearningService learningService;
    private final MentorAiClient aiClient;
    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final PersonalLearningPlanRepository personalPlanRepository;

    public PersonalMentorService(CurrentUserService currentUserService, UserProgressEntryRepository progressRepository,
                                 MentorReviewRepository reviewRepository, LearningService learningService,
                                 MentorAiClient aiClient, AppProperties properties, ObjectMapper mapper,
                                 PersonalLearningPlanRepository personalPlanRepository) {
        this.currentUserService = currentUserService;
        this.progressRepository = progressRepository;
        this.reviewRepository = reviewRepository;
        this.learningService = learningService;
        this.aiClient = aiClient;
        this.properties = properties;
        this.mapper = mapper;
        this.personalPlanRepository = personalPlanRepository;
    }

    public MentorReportDto current() {
        long userId = currentUserService.getCurrentUserId();
        return reviewRepository.findByUserIdAndReviewDate(userId, today())
                .flatMap(this::parse).orElse(null);
    }

    public List<MentorReportDto> history() {
        long userId = currentUserService.getCurrentUserId();
        return reviewRepository.findTop20ByUserIdOrderByReviewDateDesc(userId).stream()
                .map(this::parse).flatMap(Optional::stream).toList();
    }

    public MentorReportDto generate() {
        long userId = currentUserService.getCurrentUserId();
        ReentrantLock lock = USER_LOCKS.computeIfAbsent(userId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return generateLocked();
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) USER_LOCKS.remove(userId, lock);
        }
    }

    private MentorReportDto generateLocked() {
        var user = currentUserService.getCurrentUser();
        LocalDate date = today();
        var journals = progressRepository.findTop20ByUserIdOrderByEntryDateDesc(user.getId());
        RoadmapDto roadmap = learningService.roadmap();
        DailyPlanDto plan = learningService.dailyPlan();
        List<MentorAiClient.Evidence> evidence = new ArrayList<>();
        List<MentorReportDto.Observation> completed = new ArrayList<>();
        List<MentorReportDto.Observation> active = new ArrayList<>();
        List<MentorReportDto.Observation> challenges = new ArrayList<>();
        List<MentorReportDto.Observation> gaps = new ArrayList<>();
        int[] contextChars = {0};

        personalPlanRepository.findTop100ByUserIdOrderByPlanDateDescSectionAsc(user.getId()).stream().limit(20).forEach(item ->
                add(evidence, contextChars, "plan-item-" + item.getId(), "PRIOR_RECOMMENDATION_OUTCOME",
                        item.getTitle() + "; status recorded by user: " + item.getStatus().name()
                                + "; related skill: " + (item.getRelatedSkill() == null ? "not assigned" : item.getRelatedSkill()),
                        item.getPlanDate().toString()));

        add(evidence, contextChars, "career-goal", "GOAL", user.getCareerGoal(), "");
        add(evidence, contextChars, "target-roles", "ROLE", user.getTargetRoles(), "");
        user.getPreferenceList().forEach(preference -> add(evidence, contextChars,
                "interest-" + preference, "INTEREST", preference, ""));

        for (var entry : journals) {
            String day = entry.getEntryDate().toString();
            add(evidence, contextChars, "journal-" + day + "-learned", "JOURNAL", entry.getLearned(), day);
            add(evidence, contextChars, "journal-" + day + "-practiced", "JOURNAL", entry.getPracticed(), day);
            add(evidence, contextChars, "journal-" + day + "-built", "JOURNAL", entry.getBuilt(), day);
            add(evidence, contextChars, "journal-" + day + "-difficulty", "CHALLENGE", entry.getDifficulties(), day);
            add(evidence, contextChars, "journal-" + day + "-goal", "GOAL", entry.getTomorrowGoals(), day);
            add(evidence, contextChars, "journal-" + day + "-attachment", "JOURNAL", entry.getAttachmentText(), day);
            if (!blank(entry.getLearned())) active.add(new MentorReportDto.Observation("Learned", excerpt(entry.getLearned()), day));
            if (!blank(entry.getPracticed())) active.add(new MentorReportDto.Observation("Practiced", excerpt(entry.getPracticed()), day));
            if (!blank(entry.getBuilt())) active.add(new MentorReportDto.Observation("Built", excerpt(entry.getBuilt()), day));
            if (!blank(entry.getDifficulties())) challenges.add(new MentorReportDto.Observation("Challenge recorded", excerpt(entry.getDifficulties()), day));
        }

        List<LearningTopicDto> topics = roadmap.levels().stream().flatMap(level -> level.topics().stream()).toList();
        boolean userLearningHistory = false;
        for (LearningTopicDto topic : topics) {
            String state = topic.status() == null ? "NOT_STARTED" : topic.status();
            String topicEvidence = "Roadmap topic: " + topic.title() + "; current tracked status: " + state;
            if ("COMPLETED".equals(state) || "IN_PROGRESS".equals(state))
                add(evidence, contextChars, "skill-" + topic.id(), "SKILL", topic.title(), "");
            add(evidence, contextChars, "topic-status-" + topic.id(), "TOPIC_STATUS", topicEvidence,
                    topic.completedAt() == null ? "" : topic.completedAt().atZone(ZoneId.of(properties.getTimezone())).toLocalDate().toString());
            if ("COMPLETED".equals(state)) {
                userLearningHistory = true;
                completed.add(new MentorReportDto.Observation(topic.title(), "Marked completed in the learning roadmap.",
                        topic.completedAt() == null ? "" : topic.completedAt().atZone(ZoneId.of(properties.getTimezone())).toLocalDate().toString()));
            } else if ("IN_PROGRESS".equals(state)) {
                userLearningHistory = true;
                active.add(new MentorReportDto.Observation(topic.title(), "Marked in progress in the learning roadmap.", ""));
            } else if ("NOT_STARTED".equals(state)) {
                gaps.add(new MentorReportDto.Observation(topic.title(), "Not yet started in the learning roadmap.", ""));
            }
        }
        boolean completedTask = false;
        if (plan != null && plan.days() != null) {
            for (DailyPlanDto.Day day : plan.days()) {
                for (var task : day.tasks()) {
                    add(evidence, contextChars, "task-" + task.id(), "TASK",
                            task.title() + "; completed: " + task.completedInPlan(), "");
                    if (task.completedInPlan()) completedTask = true;
                }
            }
        }
        boolean hasHistory = !journals.isEmpty() || userLearningHistory || completedTask;
        if (!hasHistory) gaps.clear();

        String contextHash = hash(evidence);
        Optional<MentorReview> cached = reviewRepository.findByUserIdAndReviewDate(user.getId(), date);
        if (cached.isPresent() && cached.get().getContextHash().equals(contextHash)
                && (!properties.getAi().isEnabled() || "AI".equals(cached.get().getStatus()))) {
            Optional<MentorReportDto> report = parse(cached.get());
            if (report.isPresent()) return report.get();
        }

        MentorReportDto base = new MentorReportDto(null, "BASIC", "", null, MentorAiClient.PROMPT_VERSION,
                journals.isEmpty() ? null : journals.get(journals.size() - 1).getEntryDate(), date,
                user.getCareerGoal(), user.getTargetRoles(), List.copyOf(completed), List.copyOf(active),
                List.copyOf(challenges), hasHistory ? gaps.stream().limit(12).toList() : List.of(), List.of());
        MentorReview review = cached.orElseGet(MentorReview::new);
        if (review.getUser() == null) review.setUser(user);
        review.setReviewDate(date);
        review.setContextHash(contextHash);
        review.setPromptVersion(MentorAiClient.PROMPT_VERSION);
        review.setStatus("BASIC");
        review.setReportJson("{}"); // reserve this user/date before any provider call
        review = reviewRepository.saveAndFlush(review);
        String message;
        List<MentorReportDto.Recommendation> recommendations = List.of();
        boolean aiSucceeded = false;
        if (!hasHistory) {
            message = "There is not enough recorded learning history yet. Add a journal entry or update roadmap progress to build a useful review.";
        } else {
            Optional<MentorAiClient.Result> generated = aiClient.recommend(evidence, date);
            if (generated.isPresent() && !generated.get().recommendations().isEmpty()) {
                recommendations = generated.get().recommendations().stream().map(action -> new MentorReportDto.Recommendation(
                        action.title(), action.action(), action.rationale(), action.relatedSkill(), action.priority(), action.estimatedMinutes(),
                        action.evidenceIds().stream().map(id -> evidence.stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow())
                                .map(item -> new MentorReportDto.Observation(item.kind(), excerpt(item.text()), item.date())).toList()))
                        .toList();
                aiSucceeded = true;
                message = "Suggestions are generated from the saved context below. They are optional actions, not claims about your ability.";
            } else {
                message = generated.isPresent()
                        ? "No AI recommendation passed the evidence checks. Your saved learning record is shown without generated advice."
                        : "AI recommendations are unavailable right now. Your saved learning record is shown without generated advice.";
            }
        }
        MentorReportDto report = new MentorReportDto(review.getId(), aiSucceeded ? "AI" : "BASIC", message,
                java.time.Instant.now(), MentorAiClient.PROMPT_VERSION, base.contextFrom(), base.contextTo(), base.careerGoal(), base.targetRoles(),
                base.completedTopics(), base.skillActivity(), base.challenges(), base.learningGaps(), recommendations);
        review.setStatus(report.status());
        review.setReportJson(write(report));
        reviewRepository.save(review);
        return report;
    }

    private void add(List<MentorAiClient.Evidence> target, int[] chars, String id, String kind, String raw, String date) {
        if (blank(raw) || target.size() >= 100 || chars[0] >= MAX_CONTEXT_CHARS) return;
        String text = excerpt(raw);
        if (chars[0] + text.length() > MAX_CONTEXT_CHARS) text = text.substring(0, Math.max(0, MAX_CONTEXT_CHARS - chars[0]));
        if (text.isBlank()) return;
        target.add(new MentorAiClient.Evidence(id, kind, text, date));
        chars[0] += text.length();
    }

    private String excerpt(String value) {
        if (value == null) return "";
        String cleaned = value.trim().replaceAll("\\s+", " ");
        return cleaned.substring(0, Math.min(cleaned.length(), 500));
    }

    private String hash(List<MentorAiClient.Evidence> evidence) {
        try {
            byte[] value = MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(evidence));
            return HexFormat.of().formatHex(value);
        } catch (Exception e) {
            return HexFormat.of().formatHex(MessageDigestHolder.digest(evidence.toString().getBytes(StandardCharsets.UTF_8)));
        }
    }

    private LocalDate today() { return LocalDate.now(ZoneId.of(properties.getTimezone())); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private String write(MentorReportDto report) {
        try { return mapper.writeValueAsString(report); }
        catch (Exception e) { throw new IllegalStateException("Could not store mentor report", e); }
    }
    private Optional<MentorReportDto> parse(MentorReview review) {
        try { return Optional.of(mapper.readValue(review.getReportJson(), MentorReportDto.class)); }
        catch (Exception e) { return Optional.empty(); }
    }

    private static final class MessageDigestHolder {
        static byte[] digest(byte[] value) {
            try { return MessageDigest.getInstance("SHA-256").digest(value); }
            catch (Exception e) { throw new IllegalStateException(e); }
        }
    }
}
