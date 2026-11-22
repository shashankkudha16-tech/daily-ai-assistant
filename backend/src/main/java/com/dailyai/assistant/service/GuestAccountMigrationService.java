package com.dailyai.assistant.service;

import com.dailyai.assistant.entity.GuestMigration;
import com.dailyai.assistant.entity.GuestTopicInterest;
import com.dailyai.assistant.entity.User;
import com.dailyai.assistant.entity.UserTopicInterest;
import com.dailyai.assistant.exception.ResourceNotFoundException;
import com.dailyai.assistant.exception.UnauthorizedException;
import com.dailyai.assistant.repository.GuestArticleInteractionRepository;
import com.dailyai.assistant.repository.GuestMigrationRepository;
import com.dailyai.assistant.repository.GuestTopicInterestRepository;
import com.dailyai.assistant.repository.UserRepository;
import com.dailyai.assistant.repository.UserTopicInterestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Moves cookie-owned, anonymous topic interests into the account that just authenticated. */
@Service
public class GuestAccountMigrationService {
    private static final double HALF_LIFE_DAYS = 30.0;
    private static final double MIN_IMPORT_SCORE = 0.60;
    private static final double MAX_PER_MIGRATION = 2.0;
    private static final double MAX_ACCOUNT_SCORE = 5.0;
    private static final double EXPLICIT_PREFERENCE_BASELINE = 3.0;
    private static final int MAX_PREFERENCES = 8;
    private static final Map<String, Set<String>> TOPIC_PREFERENCES = Map.ofEntries(
            Map.entry("generative-ai", Set.of("AI_ML", "GENERATIVE_AI")),
            Map.entry("ai-agents", Set.of("AI_CODING_AGENTS", "AI_AUTOMATION")),
            Map.entry("ai-models", Set.of("AI_ML", "GENERATIVE_AI")),
            Map.entry("machine-learning", Set.of("AI_ML")),
            Map.entry("deep-learning", Set.of("AI_ML", "AI_RESEARCH")),
            Map.entry("ai-research", Set.of("AI_RESEARCH")),
            Map.entry("ai-tools", Set.of("DEVELOPER_TOOLS")),
            Map.entry("cloud-ai", Set.of("CLOUD_AI_INFRASTRUCTURE")),
            Map.entry("robotics", Set.of("AI_ML")),
            Map.entry("ai-startups", Set.of("AI_ML", "CLOUD_AI_INFRASTRUCTURE"))
    );

    private final UserRepository userRepository;
    private final GuestMigrationRepository migrationRepository;
    private final GuestTopicInterestRepository guestInterestRepository;
    private final GuestArticleInteractionRepository interactionRepository;
    private final UserTopicInterestRepository userInterestRepository;

    public GuestAccountMigrationService(UserRepository userRepository,
                                        GuestMigrationRepository migrationRepository,
                                        GuestTopicInterestRepository guestInterestRepository,
                                        GuestArticleInteractionRepository interactionRepository,
                                        UserTopicInterestRepository userInterestRepository) {
        this.userRepository = userRepository;
        this.migrationRepository = migrationRepository;
        this.guestInterestRepository = guestInterestRepository;
        this.interactionRepository = interactionRepository;
        this.userInterestRepository = userInterestRepository;
    }

    @Transactional
    public Status migrate(Long userId, String guestId) {
        if (guestId == null) return Status.NO_GUEST;
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account was not found"));

        Optional<GuestMigration> prior = migrationRepository.findById(guestId);
        if (prior.isPresent()) {
            if (!prior.get().getUser().getId().equals(userId)) {
                throw new UnauthorizedException("This guest session has already been transferred.");
            }
            return Status.ALREADY_MIGRATED;
        }

        List<GuestTopicInterest> guestInterests = guestInterestRepository.findByGuestId(guestId);
        boolean hasData = !guestInterests.isEmpty() || !interactionRepository.findByGuestId(guestId).isEmpty();
        GuestMigration tombstone = new GuestMigration();
        tombstone.setGuestId(guestId);
        tombstone.setUser(user);
        tombstone.setMigratedAt(Instant.now());
        // Claim this UUID before merging so concurrent requests cannot import it twice.
        migrationRepository.saveAndFlush(tombstone);

        Instant now = Instant.now();
        LinkedHashSet<String> preferences = new LinkedHashSet<>(user.getPreferenceList());
        for (GuestTopicInterest guestInterest : guestInterests) {
            double effective = decayed(guestInterest.getScore(), guestInterest.getUpdatedAt(), now);
            double contribution = Math.min(MAX_PER_MIGRATION, Math.max(0.0, effective));
            if (contribution > 0) mergeScore(user, guestInterest, contribution, preferences);
            if (effective >= MIN_IMPORT_SCORE) {
                for (String preference : TOPIC_PREFERENCES.getOrDefault(guestInterest.getTopic(), Set.of())) {
                    if (preferences.size() < MAX_PREFERENCES) preferences.add(preference);
                }
            }
        }
        user.setPreferences(String.join(",", preferences));
        userRepository.save(user);

        guestInterestRepository.deleteByGuestId(guestId);
        interactionRepository.deleteByGuestId(guestId);
        return hasData ? Status.MIGRATED : Status.NO_DATA;
    }

    @Transactional(readOnly = true)
    public boolean isRetired(String guestId) {
        return guestId != null && migrationRepository.existsById(guestId);
    }

    private void mergeScore(User user, GuestTopicInterest guestInterest, double contribution,
                            Set<String> currentPreferences) {
        UserTopicInterest accountInterest = userInterestRepository
                .findByUserIdAndTopic(user.getId(), guestInterest.getTopic()).orElseGet(() -> {
                    UserTopicInterest created = new UserTopicInterest();
                    created.setUser(user);
                    created.setTopic(guestInterest.getTopic());
                    boolean explicit = TOPIC_PREFERENCES.getOrDefault(guestInterest.getTopic(), Set.of())
                            .stream().anyMatch(currentPreferences::contains);
                    created.setScore(explicit ? EXPLICIT_PREFERENCE_BASELINE : 0.0);
                    created.setUpdatedAt(guestInterest.getUpdatedAt());
                    return created;
                });
        accountInterest.setScore(Math.min(MAX_ACCOUNT_SCORE, accountInterest.getScore() + contribution));
        if (guestInterest.getUpdatedAt() != null && (accountInterest.getUpdatedAt() == null
                || guestInterest.getUpdatedAt().isAfter(accountInterest.getUpdatedAt()))) {
            accountInterest.setUpdatedAt(guestInterest.getUpdatedAt());
        }
        userInterestRepository.save(accountInterest);
    }

    private double decayed(double score, Instant updatedAt, Instant now) {
        if (updatedAt == null || !now.isAfter(updatedAt)) return score;
        double days = Duration.between(updatedAt, now).toSeconds() / 86400.0;
        return score * Math.pow(0.5, days / HALF_LIFE_DAYS);
    }

    public enum Status { NO_GUEST, NO_DATA, MIGRATED, ALREADY_MIGRATED, FAILED }
}
