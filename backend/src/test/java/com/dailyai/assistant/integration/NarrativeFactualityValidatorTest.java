package com.dailyai.assistant.integration;

import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.Category;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NarrativeFactualityValidatorTest {

    private final NarrativeFactualityValidator validator = new NarrativeFactualityValidator();

    @Test
    void replacesAProductNameThatIsNotInTheSource() {
        ExplanationDraft draft = draft("OpenAI models identify features to build.");

        ExplanationDraft validated = validator.validate(draft, waveArticle());

        assertThat(validated.technicalExplanation())
                .isEqualTo(NarrativeFactualityValidator.NOT_AVAILABLE);
    }

    @Test
    void replacesTechnicalTermsThatAreNotInTheSource() {
        ExplanationDraft draft = draft("Wave uses transformer models for inference.");

        ExplanationDraft validated = validator.validate(draft, waveArticle());

        assertThat(validated.technicalExplanation())
                .isEqualTo(NarrativeFactualityValidator.NOT_AVAILABLE);
    }

    @Test
    void keepsANarrativeThatOnlyUsesSourceGroundedFacts() {
        ExplanationDraft draft = draft("Wave helps teams identify what to build with zero cloud data egress.");

        ExplanationDraft validated = validator.validate(draft, waveArticle());

        assertThat(validated.technicalExplanation())
                .isEqualTo("Wave helps teams identify what to build with zero cloud data egress.");
    }

    @Test
    void replacesTheKnownUnsupportedItIsFasterStatement() {
        ExplanationDraft draft = draft("Wave helps teams identify what to build with zero cloud data egress.");
        draft = new ExplanationDraft(draft.whatHappened(), draft.technology(), "It is faster.",
                draft.howItWorks(), draft.whereUsed(), draft.fresherLearning(), draft.practicalExample(),
                draft.relatedTechnologies(), draft.sourceEvidence(), draft.whoBenefits(), draft.useCases(),
                draft.technicalExplanation(), draft.whyItHappened(), draft.usefulToReader(),
                draft.recommendedAction(), draft.claims(), draft.metadata());

        assertThat(validator.validate(draft, waveArticle()).importance())
                .isEqualTo(NarrativeFactualityValidator.NOT_AVAILABLE);
    }

    @Test
    void rejectsPositiveClaimWhenSourceExplicitlyNegatesIt() {
        assertRejected("The trial did not show that the model is faster.", "The model is faster.");
    }

    @Test
    void preservesUncertaintyInsteadOfTurningPossibilityIntoCertainty() {
        assertRejected("The update may reduce latency.", "The update reduces latency.");
        assertAccepted("The update may reduce latency.", "The update may reduce latency.");
    }

    @Test
    void rejectsInventedNumbersAndChangedDates() {
        assertRejected("The study included 12 participants in March 2025.",
                "The study included 120 participants in March 2025.");
        assertRejected("The study included 12 participants in March 2025.",
                "The study included 12 participants in April 2025.");
    }

    @Test
    void rejectsChangedComparisonsAndDroppedQualifications() {
        assertRejected("The new model was faster than the previous version in two tests.",
                "The new model was faster than the previous version in every test.");
        assertRejected("Some users may see up to 10 percent improvement.",
                "Users will see a 10 percent improvement.");
    }

    @Test
    void permitsUnambiguousContractionParaphraseWithoutChangingPolarity() {
        assertAccepted("The trial didn't show that the model is faster.",
                "The trial did not show that the model is faster.");
    }

    @Test
    void rejectsParagraphWhenOneOfItsClaimsIsUnsupported() {
        Article article = sourcedArticle("The trial did not show that the model is faster. The trial included 12 participants.");
        ExplanationDraft draft = technicalDraft(article.getDescription(),
                "The trial included 12 participants. The model is faster.");

        assertThat(validator.validate(draft, article).technicalExplanation())
                .isEqualTo(NarrativeFactualityValidator.NOT_AVAILABLE);
    }

    @Test
    void unsupportedRecommendationUsesTheUnavailableFallback() {
        Article article = sourcedArticle("The report describes a preliminary result.");
        ExplanationDraft base = technicalDraft("The report describes a preliminary result.",
                "The report describes a preliminary result.");
        ExplanationDraft draft = new ExplanationDraft(base.whatHappened(), base.technology(), base.importance(),
                base.howItWorks(), base.whereUsed(), base.fresherLearning(), base.practicalExample(),
                base.relatedTechnologies(), base.sourceEvidence(), base.whoBenefits(), base.useCases(),
                base.technicalExplanation(), base.whyItHappened(), base.usefulToReader(),
                "Readers should sign up today.", base.claims(), base.metadata());

        assertThat(validator.validate(draft, article).recommendedAction())
                .isEqualTo(NarrativeFactualityValidator.NOT_AVAILABLE);
    }

    @Test
    void doesNotAcceptAnUnsupportedNoActionRequiredFallback() {
        Article article = sourcedArticle("The report describes a preliminary result.");
        ExplanationDraft base = technicalDraft("The report describes a preliminary result.",
                "The report describes a preliminary result.");
        ExplanationDraft draft = new ExplanationDraft(base.whatHappened(), base.technology(), base.importance(),
                base.howItWorks(), base.whereUsed(), base.fresherLearning(), base.practicalExample(),
                base.relatedTechnologies(), base.sourceEvidence(), base.whoBenefits(), base.useCases(),
                base.technicalExplanation(), base.whyItHappened(), base.usefulToReader(),
                "No specific action is required. This update is mainly for your understanding.",
                base.claims(), base.metadata());

        assertThat(validator.validate(draft, article).recommendedAction())
                .isEqualTo(NarrativeFactualityValidator.NOT_AVAILABLE);
    }

    @Test
    void rejectsEvidenceThatDoesNotOccurInTheSelectedSource() {
        Article article = sourcedArticle("The report describes a preliminary result.");
        ExplanationDraft draft = technicalDraft("The trial included 12 participants.",
                "The trial included 12 participants.");

        ExplanationDraft validated = validator.validate(draft, article);

        assertThat(validated.technicalExplanation()).isEqualTo(NarrativeFactualityValidator.NOT_AVAILABLE);
        assertThat(validated.claims()).isEmpty();
    }

    private void assertRejected(String source, String claim) {
        Article article = sourcedArticle(source);
        assertThat(validator.validate(technicalDraft(source, claim), article).technicalExplanation())
                .isEqualTo(NarrativeFactualityValidator.NOT_AVAILABLE);
    }

    private void assertAccepted(String source, String claim) {
        Article article = sourcedArticle(source);
        assertThat(validator.validate(technicalDraft(source, claim), article).technicalExplanation())
                .isEqualTo(claim);
    }

    private Article sourcedArticle(String source) {
        Article article = new Article();
        article.setTitle("Source fixture");
        article.setDescription(source);
        article.setCategory(new Category("Testing", "testing", null));
        return article;
    }

    private ExplanationDraft technicalDraft(String evidence, String claim) {
        String unavailable = NarrativeFactualityValidator.NOT_AVAILABLE;
        return new ExplanationDraft(unavailable, unavailable, unavailable, unavailable, unavailable,
                unavailable, unavailable, unavailable, evidence, unavailable, unavailable, claim,
                unavailable, unavailable, unavailable,
                List.of(new ExplanationDraft.Claim("technicalExplanation", ExplanationDraft.Claim.Kind.SOURCE_FACT,
                        claim, evidence)), ExplanationDraft.Metadata.none());
    }

    @Test
    void validatesAgainstFullPublisherSourceWhenFeedExcerptIsInsufficient() {
        Article article = waveArticle();
        article.setDescription("Short excerpt.");
        ArticleSourceMaterial full = new ArticleSourceMaterial(ArticleSourceMaterial.Type.FULL_ARTICLE,
                ArticleSourceMaterial.Completeness.COMPLETE,
                "Wave introduces a builder. It uses transformer models to help product teams choose features.");
        String evidence = "It uses transformer models to help product teams choose features.";
        ExplanationDraft draft = new ExplanationDraft(
                "Wave introduces a builder.", "transformer models", "Not available in the provided text.",
                "Not available in the provided text.", "Not available in the provided text.",
                "Not available in the provided text.", "Not available in the provided text.",
                "Not available in the provided text.", evidence,
                "Not available in the provided text.", "Not available in the provided text.",
                "It uses transformer models to help product teams choose features.",
                List.of(new ExplanationDraft.Claim("technicalExplanation", ExplanationDraft.Claim.Kind.SOURCE_FACT,
                        evidence, evidence)), ExplanationDraft.Metadata.none());

        ExplanationDraft validated = validator.validate(draft, article, full);

        assertThat(validated.technicalExplanation()).isEqualTo(evidence);
        assertThat(validated.hasSourceEvidence(article, full)).isTrue();
        assertThat(validated.hasSourceEvidence(article)).isFalse();
    }

    @Test
    void replacesAnInventedNumber() {
        ExplanationDraft draft = draft("Wave supports 500 million developers.");

        ExplanationDraft validated = validator.validate(draft, waveArticle());

        assertThat(validated.technicalExplanation())
                .isEqualTo(NarrativeFactualityValidator.NOT_AVAILABLE);
    }

    @Test
    void dropsClaimsThatContradictOrParaphraseTheSourceWithoutExactSupport() {
        ExplanationDraft draft = draft("Wave helps teams identify what to build.");
        ExplanationDraft.Claim claim = new ExplanationDraft.Claim(
                "technicalExplanation",
                ExplanationDraft.Claim.Kind.SOURCE_FACT,
                "Wave ensures zero cloud data egress.",
                "with zero cloud data egress");
        draft = new ExplanationDraft(draft.whatHappened(), draft.technology(), draft.importance(),
                draft.howItWorks(), draft.whereUsed(), draft.fresherLearning(),
                draft.practicalExample(), draft.relatedTechnologies(), draft.sourceEvidence(),
                draft.whoBenefits(), draft.useCases(), draft.technicalExplanation(),
                List.of(claim), draft.metadata());

        ExplanationDraft validated = validator.validate(draft, waveArticle());

        assertThat(validated.claims()).isEmpty();
    }

    @Test
    void leavesAnHonestClaimAsASourceFact() {
        ExplanationDraft draft = draft("Wave helps teams identify what to build.");
        ExplanationDraft.Claim claim = new ExplanationDraft.Claim(
                "technicalExplanation",
                ExplanationDraft.Claim.Kind.SOURCE_FACT,
                "Wave helps teams identify what to build with zero cloud data egress.",
                "Wave helps teams identify what to build with zero cloud data egress.");
        draft = new ExplanationDraft(draft.whatHappened(), draft.technology(), draft.importance(),
                draft.howItWorks(), draft.whereUsed(), draft.fresherLearning(),
                draft.practicalExample(), draft.relatedTechnologies(), draft.sourceEvidence(),
                draft.whoBenefits(), draft.useCases(), draft.technicalExplanation(),
                List.of(claim), draft.metadata());

        ExplanationDraft validated = validator.validate(draft, waveArticle());

        assertThat(validated.claims()).hasSize(1);
        assertThat(validated.claims().get(0).kind())
                .isEqualTo(ExplanationDraft.Claim.Kind.SOURCE_FACT);
    }

    @Test
    void audienceRolesAreNotTreatedAsInventedProductNames() {
        ExplanationDraft draft = new ExplanationDraft(
                "Not available in the provided text.",
                "Not available in the provided text.",
                "Not available in the provided text.",
                "Not available in the provided text.",
                "Not available in the provided text.",
                "Not available in the provided text.",
                "Not available in the provided text.",
                "Not available in the provided text.",
                "Wave helps teams identify what to build.",
                "Developers comparing tools.",
                "Not available in the provided text.",
                "Not available in the provided text.",
                List.of(new ExplanationDraft.Claim(
                        "whoBenefits", ExplanationDraft.Claim.Kind.SOURCE_FACT,
                        "Developers comparing tools.", "Wave helps teams")),
                ExplanationDraft.Metadata.none());

        ExplanationDraft validated = validator.validate(draft, waveArticle());

        assertThat(validated.whoBenefits()).isEqualTo(NarrativeFactualityValidator.NOT_AVAILABLE);
    }

    private ExplanationDraft draft(String technicalExplanation) {
        String unavailable = NarrativeFactualityValidator.NOT_AVAILABLE;
        return new ExplanationDraft(
                unavailable, unavailable, unavailable, unavailable, unavailable,
                unavailable, unavailable, unavailable,
                "Wave helps teams identify what to build with zero cloud data egress.",
                unavailable, unavailable, technicalExplanation,
                List.of(new ExplanationDraft.Claim(
                        "technicalExplanation", ExplanationDraft.Claim.Kind.SOURCE_FACT,
                        "Wave helps teams identify what to build with zero cloud data egress.",
                        "Wave helps teams identify what to build with zero cloud data egress.")),
                ExplanationDraft.Metadata.none());
    }

    private Article waveArticle() {
        Category category = new Category("AI Tools", "ai-tools", null);
        Article article = new Article();
        article.setId(16L);
        article.setTitle("Wave launches a builder for product teams");
        article.setDescription("Wave helps teams identify what to build with zero cloud data egress.");
        article.setCategory(category);
        return article;
    }
}
