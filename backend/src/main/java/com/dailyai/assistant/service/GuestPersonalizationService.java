package com.dailyai.assistant.service;

import com.dailyai.assistant.dto.ArticleDto;
import com.dailyai.assistant.dto.GuestInteractionRequest;
import com.dailyai.assistant.dto.GuestRecommendationsDto;
import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.GuestArticleInteraction;
import com.dailyai.assistant.entity.GuestTopicInterest;
import com.dailyai.assistant.exception.ResourceNotFoundException;
import com.dailyai.assistant.exception.UnauthorizedException;
import com.dailyai.assistant.repository.ArticleRepository;
import com.dailyai.assistant.repository.GuestArticleInteractionRepository;
import com.dailyai.assistant.repository.GuestMigrationRepository;
import com.dailyai.assistant.repository.GuestTopicInterestRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Service
public class GuestPersonalizationService {
    private static final int MAX_RECOMMENDATIONS = 12;
    private static final int CANDIDATE_LIMIT = 500;
    private static final double OPEN_WEIGHT = 0.20;
    private static final double VIEW_WEIGHT = 1.25;
    private static final double SEARCH_WEIGHT = 0.45;
    private static final double RECOMMENDATION_MATCH_THRESHOLD = 0.60;
    private static final double HALF_LIFE_DAYS = 30.0;
    private static final Map<String, Pattern> TOPIC_TERMS = Map.ofEntries(
            Map.entry("generative-ai", Pattern.compile("\\b(generative ai|llm|llms|large language model|chatgpt|gpt|image generation|text generation)\\b")),
            Map.entry("ai-agents", Pattern.compile("\\b(ai agent|agents|agentic|tool calling|multi-agent)\\b")),
            Map.entry("ai-models", Pattern.compile("\\b(foundation model|open[- ]weight|ai model|model release|language model)\\b")),
            Map.entry("machine-learning", Pattern.compile("\\b(machine learning|supervised learning|training data|inference)\\b")),
            Map.entry("deep-learning", Pattern.compile("\\b(deep learning|neural network|transformer model)\\b")),
            Map.entry("ai-research", Pattern.compile("\\b(research|study|studies|paper|arxiv|benchmark)\\b")),
            Map.entry("ai-tools", Pattern.compile("\\b(ai tools?|developer tools?|assistant apps?|software tools?)\\b")),
            Map.entry("cybersecurity", Pattern.compile("\\b(cybersecurity|cyber security|security|vulnerability|malware|phishing)\\b")),
            Map.entry("cloud-ai", Pattern.compile("\\b(cloud|gpu|chip|data center|infrastructure|compute)\\b")),
            Map.entry("robotics", Pattern.compile("\\b(robot|robotics|humanoid)\\b")),
            Map.entry("ai-startups", Pattern.compile("\\b(startup|funding|venture capital|investment)\\b"))
    );
    private static final Set<String> CATEGORY_TOPICS = Set.of(
            "generative-ai", "ai-agents", "ai-models", "machine-learning", "deep-learning",
            "ai-research", "ai-tools", "cybersecurity", "cloud-ai", "robotics", "ai-startups");

    private final ArticleRepository articleRepository;
    private final GuestArticleInteractionRepository interactionRepository;
    private final GuestTopicInterestRepository interestRepository;
    private final GuestMigrationRepository migrationRepository;

    public GuestPersonalizationService(ArticleRepository articleRepository,
                                       GuestArticleInteractionRepository interactionRepository,
                                       GuestTopicInterestRepository interestRepository,
                                       GuestMigrationRepository migrationRepository) {
        this.articleRepository = articleRepository;
        this.interactionRepository = interactionRepository;
        this.interestRepository = interestRepository;
        this.migrationRepository = migrationRepository;
    }

    @Transactional
    public void recordInteraction(String guestId, GuestInteractionRequest request) {
        requireActiveGuest(guestId);
        Article article = articleRepository.findById(request.articleId())
                .orElseThrow(() -> new ResourceNotFoundException("Article " + request.articleId() + " was not found"));
        Instant now = Instant.now();
        GuestArticleInteraction interaction = interactionRepository.findByGuestIdAndArticleId(guestId, article.getId())
                .orElseGet(() -> newInteraction(guestId, article));
        double weight;
        if (request.type() == GuestInteractionRequest.Type.OPEN) {
            interaction.setOpenCount(Math.min(100, interaction.getOpenCount() + 1));
            interaction.setOpenedAt(now);
            weight = OPEN_WEIGHT;
        } else {
            interaction.setQualifiedViewCount(Math.min(100, interaction.getQualifiedViewCount() + 1));
            interaction.setQualifiedViewedAt(now);
            weight = VIEW_WEIGHT;
        }
        interactionRepository.save(interaction);
        updateInterests(guestId, topics(article), weight, now);
    }

    /** Stores only extracted topic labels; the submitted search text is never persisted. */
    @Transactional
    public void recordSearch(String guestId, String query) {
        requireActiveGuest(guestId);
        if (query == null || query.isBlank()) return;
        Set<String> topics = topicsFromText(query);
        updateInterests(guestId, topics, SEARCH_WEIGHT, Instant.now());
    }

    @Transactional
    public GuestRecommendationsDto recommendations(String guestId, int requestedLimit) {
        requireActiveGuest(guestId);
        int limit = Math.max(1, Math.min(requestedLimit, MAX_RECOMMENDATIONS));
        Instant now = Instant.now();
        Map<String, Double> scores = effectiveScores(guestId, now);
        Set<Long> excluded = new HashSet<>();
        interactionRepository.findByGuestId(guestId).stream()
                .filter(value -> value.getOpenedAt() != null || value.getRecommendedAt() != null)
                .map(value -> value.getArticle().getId()).forEach(excluded::add);

        List<Article> candidates = articleRepository.findAll(PageRequest.of(0, CANDIDATE_LIMIT,
                Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id")))).getContent().stream()
                .filter(article -> !excluded.contains(article.getId()))
                .sorted(Comparator.comparing(Article::getPublishedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(Article::getId, Comparator.reverseOrder()))
                .toList();

        Map<Article, Double> articleScores = new LinkedHashMap<>();
        for (Article article : candidates) articleScores.put(article, scoreArticle(article, scores));
        List<Article> matching = articleScores.entrySet().stream()
                .filter(entry -> entry.getValue() >= RECOMMENDATION_MATCH_THRESHOLD)
                .sorted(Map.Entry.<Article, Double>comparingByValue().reversed())
                .map(Map.Entry::getKey).toList();
        List<Article> selected = new ArrayList<>();
        int personalizedSlots = scores.isEmpty() ? 0 : Math.max(1, (limit * 2) / 3);
        selected.addAll(matching.stream().limit(personalizedSlots).toList());

        // Reserve space for recent stories outside the strongest matched interests.
        int exploreSlots = scores.isEmpty() ? limit : Math.max(1, limit - selected.size());
        articleScores.keySet().stream()
                .filter(article -> !selected.contains(article))
                .filter(article -> scoreArticle(article, scores) < RECOMMENDATION_MATCH_THRESHOLD)
                .limit(exploreSlots)
                .forEach(selected::add);
        if (selected.size() < limit) {
            articleScores.keySet().stream().filter(article -> !selected.contains(article))
                    .limit(limit - selected.size()).forEach(selected::add);
        }

        for (Article article : selected) {
            GuestArticleInteraction interaction = interactionRepository.findByGuestIdAndArticleId(guestId, article.getId())
                    .orElseGet(() -> newInteraction(guestId, article));
            interaction.setRecommendedAt(now);
            interactionRepository.save(interaction);
        }
        List<ArticleDto> dtos = selected.stream().map(article -> ArticleDto.from(article, false, null)).toList();
        boolean personalized = !scores.isEmpty() && selected.stream()
                .anyMatch(article -> scoreArticle(article, scores) >= RECOMMENDATION_MATCH_THRESHOLD);
        return new GuestRecommendationsDto(dtos, personalized, personalized
                ? "Based on your reading activity, with recent stories included for discovery."
                : "Your recommendations will improve as you explore articles.");
    }

    @Transactional
    public void reset(String guestId) {
        requireActiveGuest(guestId);
        interactionRepository.deleteByGuestId(guestId);
        interestRepository.deleteByGuestId(guestId);
    }

    @Transactional(readOnly = true)
    public Map<String, Double> scoresForTesting(String guestId) {
        return effectiveScores(guestId, Instant.now());
    }

    private void updateInterests(String guestId, Set<String> topics, double weight, Instant now) {
        for (String topic : topics) {
            GuestTopicInterest interest = interestRepository.findByGuestIdAndTopic(guestId, topic)
                    .orElseGet(() -> {
                        GuestTopicInterest created = new GuestTopicInterest();
                        created.setGuestId(guestId);
                        created.setTopic(topic);
                        created.setScore(0.0);
                        created.setUpdatedAt(now);
                        return created;
                    });
            interest.setScore(decayed(interest.getScore(), interest.getUpdatedAt(), now) + weight);
            interest.setUpdatedAt(now);
            interestRepository.save(interest);
        }
    }

    private Map<String, Double> effectiveScores(String guestId, Instant now) {
        Map<String, Double> scores = new HashMap<>();
        for (GuestTopicInterest interest : interestRepository.findByGuestId(guestId)) {
            double effective = decayed(interest.getScore(), interest.getUpdatedAt(), now);
            if (effective >= 0.05) scores.put(interest.getTopic(), effective);
        }
        return scores;
    }

    private double decayed(double score, Instant updatedAt, Instant now) {
        if (updatedAt == null || !now.isAfter(updatedAt)) return score;
        double days = Duration.between(updatedAt, now).toSeconds() / 86400.0;
        return score * Math.pow(0.5, days / HALF_LIFE_DAYS);
    }

    private double scoreArticle(Article article, Map<String, Double> interests) {
        return topics(article).stream().mapToDouble(topic -> interests.getOrDefault(topic, 0.0)).sum();
    }

    private Set<String> topics(Article article) {
        Set<String> topics = new LinkedHashSet<>();
        if (article.getCategory() != null && CATEGORY_TOPICS.contains(article.getCategory().getSlug())) {
            topics.add(article.getCategory().getSlug());
        }
        topics.addAll(topicsFromText((article.getTitle() == null ? "" : article.getTitle()) + " "
                + (article.getDescription() == null ? "" : article.getDescription())));
        return topics;
    }

    private Set<String> topicsFromText(String text) {
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT);
        Set<String> result = new LinkedHashSet<>();
        TOPIC_TERMS.forEach((topic, pattern) -> {
            if (pattern.matcher(normalized).find()) result.add(topic);
        });
        return result;
    }

    private GuestArticleInteraction newInteraction(String guestId, Article article) {
        GuestArticleInteraction interaction = new GuestArticleInteraction();
        interaction.setGuestId(guestId);
        interaction.setArticle(article);
        return interaction;
    }

    private void requireActiveGuest(String guestId) {
        if (migrationRepository.existsById(guestId)) {
            throw new UnauthorizedException("This guest session has already been transferred.");
        }
    }
}
