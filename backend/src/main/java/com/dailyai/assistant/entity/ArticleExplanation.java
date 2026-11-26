package com.dailyai.assistant.entity;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Beginner friendly explanation of an article. The {@code generatedBy} column tells
 * the client whether the text came from an LLM, from the offline template or from
 * the publisher's own description.
 */
@Entity
@Table(name = "article_explanations",
        uniqueConstraints = @UniqueConstraint(name = "uk_explanations_article", columnNames = "article_id"))
public class ArticleExplanation {

    public enum GeneratedBy {
        AI, BASIC
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "article_id", nullable = false)
    private Article article;

    @Column(name = "what_happened", columnDefinition = "TEXT")
    private String whatHappened;

    @Column(columnDefinition = "TEXT")
    private String technology;

    @Column(columnDefinition = "TEXT")
    private String importance;

    @Column(name = "how_it_works", columnDefinition = "TEXT")
    private String howItWorks;

    @Column(name = "where_used", columnDefinition = "TEXT")
    private String whereUsed;

    @Column(name = "fresher_learning", columnDefinition = "TEXT")
    private String fresherLearning;

    @Column(name = "practical_example", columnDefinition = "TEXT")
    private String practicalExample;

    @Column(name = "related_technologies", columnDefinition = "TEXT")
    private String relatedTechnologies;

    @Column(name = "who_benefits", columnDefinition = "TEXT")
    private String whoBenefits;

    @Column(name = "use_cases", columnDefinition = "TEXT")
    private String useCases;

    @Column(name = "technical_explanation", columnDefinition = "TEXT")
    private String technicalExplanation;

    @Column(name = "why_it_happened", columnDefinition = "TEXT")
    private String whyItHappened;

    @Column(name = "useful_to_reader", columnDefinition = "TEXT")
    private String usefulToReader;

    @Column(name = "recommended_action", columnDefinition = "TEXT")
    private String recommendedAction;

    @Column(name = "source_type", length = 20)
    private String sourceType;

    @Column(name = "source_completeness", length = 20, nullable = false)
    private String sourceCompleteness = "UNKNOWN";

    /** JSON array of {statement, evidence} pairs, each quote verified against the feed text. */
    @Column(name = "claims_json", columnDefinition = "TEXT")
    private String claimsJson;

    @Column(name = "source_evidence", columnDefinition = "TEXT")
    private String sourceEvidence;

    @Column(length = 50)
    private String provider;

    @Column(length = 100)
    private String model;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "ai_attempted_at")
    private Instant aiAttemptedAt;

    @Column(name = "ai_attempts", nullable = false)
    private int aiAttempts = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "generated_by", nullable = false, length = 20)
    private GeneratedBy generatedBy = GeneratedBy.BASIC;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Article getArticle() {
        return article;
    }

    public void setArticle(Article article) {
        this.article = article;
    }

    public String getWhatHappened() {
        return whatHappened;
    }

    public void setWhatHappened(String whatHappened) {
        this.whatHappened = whatHappened;
    }

    public String getTechnology() {
        return technology;
    }

    public void setTechnology(String technology) {
        this.technology = technology;
    }

    public String getImportance() {
        return importance;
    }

    public void setImportance(String importance) {
        this.importance = importance;
    }

    public String getHowItWorks() {
        return howItWorks;
    }

    public void setHowItWorks(String howItWorks) {
        this.howItWorks = howItWorks;
    }

    public String getWhereUsed() {
        return whereUsed;
    }

    public void setWhereUsed(String whereUsed) {
        this.whereUsed = whereUsed;
    }

    public String getFresherLearning() {
        return fresherLearning;
    }

    public void setFresherLearning(String fresherLearning) {
        this.fresherLearning = fresherLearning;
    }

    public String getPracticalExample() {
        return practicalExample;
    }

    public void setPracticalExample(String practicalExample) {
        this.practicalExample = practicalExample;
    }

    public String getRelatedTechnologies() {
        return relatedTechnologies;
    }

    public void setRelatedTechnologies(String relatedTechnologies) {
        this.relatedTechnologies = relatedTechnologies;
    }

    public String getWhoBenefits() {
        return whoBenefits;
    }

    public void setWhoBenefits(String whoBenefits) {
        this.whoBenefits = whoBenefits;
    }

    public String getUseCases() {
        return useCases;
    }

    public void setUseCases(String useCases) {
        this.useCases = useCases;
    }

    public String getTechnicalExplanation() {
        return technicalExplanation;
    }

    public void setTechnicalExplanation(String technicalExplanation) {
        this.technicalExplanation = technicalExplanation;
    }

    public String getWhyItHappened() { return whyItHappened; }
    public void setWhyItHappened(String whyItHappened) { this.whyItHappened = whyItHappened; }
    public String getUsefulToReader() { return usefulToReader; }
    public void setUsefulToReader(String usefulToReader) { this.usefulToReader = usefulToReader; }
    public String getRecommendedAction() { return recommendedAction; }
    public void setRecommendedAction(String recommendedAction) { this.recommendedAction = recommendedAction; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getSourceCompleteness() { return sourceCompleteness; }
    public void setSourceCompleteness(String sourceCompleteness) { this.sourceCompleteness = sourceCompleteness; }

    public String getClaimsJson() {
        return claimsJson;
    }

    public void setClaimsJson(String claimsJson) {
        this.claimsJson = claimsJson;
    }

    public String getSourceEvidence() {
        return sourceEvidence;
    }

    public void setSourceEvidence(String sourceEvidence) {
        this.sourceEvidence = sourceEvidence;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getInputTokens() {
        return inputTokens;
    }

    public void setInputTokens(Integer inputTokens) {
        this.inputTokens = inputTokens;
    }

    public Integer getOutputTokens() {
        return outputTokens;
    }

    public void setOutputTokens(Integer outputTokens) {
        this.outputTokens = outputTokens;
    }

    public Integer getLatencyMs() {
        return latencyMs;
    }

    public void setLatencyMs(Integer latencyMs) {
        this.latencyMs = latencyMs;
    }

    public Instant getAiAttemptedAt() {
        return aiAttemptedAt;
    }

    public void setAiAttemptedAt(Instant aiAttemptedAt) {
        this.aiAttemptedAt = aiAttemptedAt;
    }

    public int getAiAttempts() {
        return aiAttempts;
    }

    public void setAiAttempts(int aiAttempts) {
        this.aiAttempts = aiAttempts;
    }

    public GeneratedBy getGeneratedBy() {
        return generatedBy;
    }

    public void setGeneratedBy(GeneratedBy generatedBy) {
        this.generatedBy = generatedBy;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }

    public void setGeneratedAt(Instant generatedAt) {
        this.generatedAt = generatedAt;
    }
}
