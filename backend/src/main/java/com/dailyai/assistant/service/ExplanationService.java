package com.dailyai.assistant.service;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.ArticleExplanation;
import com.dailyai.assistant.integration.ExplanationDraft;
import com.dailyai.assistant.integration.ExplanationProvider;
import com.dailyai.assistant.integration.ArticleSourceMaterial;
import com.dailyai.assistant.integration.NarrativeFactualityValidator;
import com.dailyai.assistant.repository.ArticleExplanationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class ExplanationService {

    private static final Logger log = LoggerFactory.getLogger(ExplanationService.class);
    private static final String UNKNOWN = "Not available in the provided text.";

    private final ArticleExplanationRepository explanationRepository;
    private final ExplanationProvider explanationProvider;
    private final ObjectMapper objectMapper;
    private final int retryAfterMinutes;
    private final PublisherArticleContentPersistenceService publisherContent;
    private final NarrativeFactualityValidator factualityValidator;

    public ExplanationService(ArticleExplanationRepository explanationRepository,
                              ExplanationProvider explanationProvider,
                              ObjectMapper objectMapper,
                              AppProperties properties,
                              PublisherArticleContentPersistenceService publisherContent,
                              NarrativeFactualityValidator factualityValidator) {
        this.explanationRepository = explanationRepository;
        this.explanationProvider = explanationProvider;
        this.objectMapper = objectMapper;
        this.retryAfterMinutes = Math.max(0, properties.getAi().getRetryAfterMinutes());
        this.publisherContent = publisherContent;
        this.factualityValidator = factualityValidator;
    }

    /**
     * Builds a truthful offline explanation using only the feed data. It never invents
     * technical details; fields the feed cannot support say so explicitly.
     */
    @Transactional
    public ArticleExplanation createBasic(Article article) {
        Optional<ArticleExplanation> existing = explanationRepository.findByArticleId(article.getId());
        if (existing.isPresent()) {
            return existing.get();
        }
        ArticleExplanation explanation = new ArticleExplanation();
        explanation.setArticle(article);
        fillBasic(explanation, article);
        explanation.setGeneratedBy(ArticleExplanation.GeneratedBy.BASIC);
        explanation.setGeneratedAt(Instant.now());
        return explanationRepository.save(explanation);
    }

    @Transactional
    public ArticleExplanation getOrCreate(Article article, boolean allowAi) {
        Optional<ArticleExplanation> existing = explanationRepository.findByArticleId(article.getId());
        if (existing.isEmpty()) {
            ArticleExplanation created = createBasic(article);
            if (allowAi) {
                return upgradeWithAi(created, article, false);
            }
            return created;
        }

        ArticleExplanation explanation = existing.get();
        if (explanation.getGeneratedBy() == ArticleExplanation.GeneratedBy.BASIC) {
            // Refresh old BASIC rows using current source-grounded templates so persisted
            // pre-Phase-4 generic category claims are not shown on existing articles.
            fillBasic(explanation, article);
            explanationRepository.save(explanation);
            if (allowAi) {
                return upgradeWithAi(explanation, article, false);
            }
        } else if (allowAi && explanation.getWhyItHappened() == null) {
            // Existing AI rows are upgraded lazily, one requested article at a time.
            return upgradeWithAi(explanation, article, false);
        }
        return explanation;
    }

    @Transactional
    public ArticleExplanation regenerateWithAi(Article article) {
        ArticleExplanation explanation = explanationRepository.findByArticleId(article.getId())
                .orElseGet(() -> createBasic(article));
        return upgradeWithAi(explanation, article, true);
    }

    public boolean isAiEnabled() {
        return explanationProvider.isEnabled();
    }

    private ArticleExplanation upgradeWithAi(ArticleExplanation explanation, Article article, boolean forced) {
        if (!explanationProvider.isEnabled()) {
            return explanation;
        }
        if (!explanationProvider.isAvailable()) {
            return explanation;
        }
        if (!forced && recentlyAttempted(explanation)) {
            log.debug("Skipping AI retry for article {}: attempted at {}", article.getId(),
                    explanation.getAiAttemptedAt());
            return explanation;
        }

        explanation.setAiAttemptedAt(Instant.now());
        explanation.setAiAttempts(explanation.getAiAttempts() + 1);
        ArticleSourceMaterial source = publisherContent.resolve(article);
        Optional<ExplanationDraft> draft = explanationProvider.generateExplanation(article, source)
                .map(value -> factualityValidator.validate(value, article, source))
                .filter(ExplanationDraft::isComplete)
                .filter(value -> value.hasSourceEvidence(article, source))
                .filter(value -> value.hasGroundedSourceFact(article, source));

        if (draft.isEmpty()) {
            // Persist the attempt timestamp so a broken provider does not spend budget
            // again on every page load. The BASIC text stays visible.
            explanationRepository.save(explanation);
            return explanation;
        }

        ExplanationDraft value = draft.get();
        explanation.setWhatHappened(value.whatHappened());
        explanation.setTechnology(value.technology());
        explanation.setImportance(value.importance());
        explanation.setHowItWorks(value.howItWorks());
        explanation.setWhereUsed(value.whereUsed());
        explanation.setFresherLearning(value.fresherLearning());
        explanation.setPracticalExample(value.practicalExample());
        explanation.setRelatedTechnologies(value.relatedTechnologies());
        explanation.setWhoBenefits(value.whoBenefits());
        explanation.setUseCases(value.useCases());
        explanation.setTechnicalExplanation(value.technicalExplanation());
        explanation.setWhyItHappened(value.whyItHappened());
        explanation.setUsefulToReader(value.usefulToReader());
        explanation.setRecommendedAction(value.recommendedAction());
        explanation.setSourceType(source.type().name());
        explanation.setSourceCompleteness(source.completeness().name());
        explanation.setSourceEvidence(value.sourceEvidence());
        explanation.setClaimsJson(toJson(value.supportedClaims(article, source)));

        ExplanationDraft.Metadata metadata = value.metadata() == null
                ? ExplanationDraft.Metadata.none() : value.metadata();
        explanation.setProvider(metadata.provider());
        explanation.setModel(metadata.model());
        explanation.setInputTokens(metadata.inputTokens());
        explanation.setOutputTokens(metadata.outputTokens());
        explanation.setLatencyMs((int) metadata.latencyMs());

        explanation.setGeneratedBy(ArticleExplanation.GeneratedBy.AI);
        explanation.setGeneratedAt(Instant.now());
        log.info("Generated AI explanation for article {} with {} supported claims",
                article.getId(), value.supportedClaims(article, source).size());
        return explanationRepository.save(explanation);
    }

    private boolean recentlyAttempted(ArticleExplanation explanation) {
        Instant attemptedAt = explanation.getAiAttemptedAt();
        if (attemptedAt == null) {
            return false;
        }
        Duration window = Duration.ofMinutes(retryAfterMinutes);
        return retryAfterMinutes > 0 && attemptedAt.plus(window).isAfter(Instant.now());
    }

    private String toJson(List<ExplanationDraft.Claim> claims) {
        try {
            return objectMapper.writeValueAsString(claims);
        } catch (Exception ex) {
            log.warn("Could not serialise claim evidence: {}", ex.getMessage());
            return null;
        }
    }

    private void fillBasic(ArticleExplanation explanation, Article article) {
        ArticleSourceMaterial source = ArticleSourceMaterial.fromFeed(article);
        String description = source.text();

        explanation.setWhatHappened(basicWhatHappened(description, article.getTitle()));
        explanation.setWhyItHappened(UNKNOWN);
        explanation.setImportance(UNKNOWN);
        explanation.setUsefulToReader(UNKNOWN);
        explanation.setRecommendedAction(UNKNOWN);
        explanation.setSourceType(source.type().name());
        explanation.setSourceCompleteness(source.completeness().name());

        explanation.setTechnology(UNKNOWN);

        explanation.setHowItWorks(UNKNOWN);

        explanation.setWhereUsed(UNKNOWN);

        explanation.setFresherLearning(UNKNOWN);

        explanation.setPracticalExample(UNKNOWN);

        explanation.setRelatedTechnologies(UNKNOWN);

        explanation.setWhoBenefits(UNKNOWN);

        explanation.setUseCases(UNKNOWN);

        explanation.setTechnicalExplanation(UNKNOWN);

        // BASIC text is publisher text plus transparent templates; there are no claimed
        // AI interpretations and therefore no claim-level evidence to persist.
        explanation.setClaimsJson(null);
        explanation.setSourceEvidence(null);
        explanation.setProvider(null);
        explanation.setModel(null);
        explanation.setInputTokens(null);
        explanation.setOutputTokens(null);
        explanation.setLatencyMs(null);
    }

    private String basicWhatHappened(String description, String title) {
        String text = description == null ? "" : description.trim();
        if (text.isBlank()) {
            return "The publisher did not provide a summary. The headline says: " + title;
        }
        // Feed descriptions can be long; keep fallback text short and avoid inventing a rewrite.
        if (text.length() <= 500) return text;
        int boundary = text.lastIndexOf(' ', 497);
        return text.substring(0, boundary > 0 ? boundary : 497).stripTrailing() + "…";
    }
}
