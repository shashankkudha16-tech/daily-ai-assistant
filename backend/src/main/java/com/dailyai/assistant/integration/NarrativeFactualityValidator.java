package com.dailyai.assistant.integration;

import com.dailyai.assistant.entity.Article;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic guard between the provider and the stored explanation.
 *
 * <p>The model is allowed to explain and interpret, but it is not allowed to
 * introduce product names, numbers or technical terms that are missing from the
 * publisher text. A narrative field that fails the check is replaced by the
 * explicit unavailable placeholder; a draft with no grounded source fact is
 * rejected by {@link AiClient}.</p>
 */
@Component
public class NarrativeFactualityValidator {

    public static final String NOT_AVAILABLE = "Not available in the provided text.";

    private static final Logger log = LoggerFactory.getLogger(NarrativeFactualityValidator.class);
    private final SourceClaimVerifier claimVerifier = new SourceClaimVerifier();

    private static final Pattern CAPITALIZED_WORD =
            Pattern.compile("\\b\\p{Lu}[\\p{L}\\p{N}-]{1,}\\b");
    private static final Pattern NUMBER = Pattern.compile("\\b\\d[\\d.,%+-]*\\b");

    private static final Set<String> NON_ANCHOR_CAPITALIZED = Set.of(
            "a", "an", "the", "this", "that", "these", "those", "it", "its",
            "in", "on", "at", "for", "from", "with", "without", "by", "to", "of",
            "and", "or", "but", "if", "when", "while", "after", "before",
            "learn", "build", "read", "try", "use", "using", "consider", "start",
            "understand", "check", "follow", "open", "compare", "track",
            "developers", "readers", "users", "teams", "businesses", "companies",
            "people", "students", "engineers", "researchers", "organizations",
            "beginners", "freshers", "professionals", "managers", "customers",
            "consumers", "anyone", "everyone", "practitioners", "scientists",
            "not", "no", "yes", "however", "because", "although"
    );

    /**
     * Technical terms are treated as factual claims about the source. The value
     * lists accepted source spellings, including simple plural/industry variants.
     */
    private static final Map<String, List<String>> TECHNICAL_TERMS = Map.ofEntries(
            Map.entry("transformer", List.of("transformer", "transformers")),
            Map.entry("transformers", List.of("transformer", "transformers")),
            Map.entry("inference", List.of("inference", "inferencing", "infer")),
            Map.entry("fine-tuning", List.of("fine-tuning", "fine tuning", "fine-tune", "fine tune")),
            Map.entry("neural network", List.of("neural network", "neural networks")),
            Map.entry("neural networks", List.of("neural network", "neural networks")),
            Map.entry("large language model", List.of("large language model", "large language models")),
            Map.entry("large language models", List.of("large language model", "large language models")),
            Map.entry("llm", List.of("llm", "llms", "large language model", "large language models")),
            Map.entry("llms", List.of("llm", "llms", "large language model", "large language models")),
            Map.entry("rag", List.of("rag", "retrieval augmented generation", "retrieval-augmented generation")),
            Map.entry("retrieval augmented generation",
                    List.of("retrieval augmented generation", "retrieval-augmented generation", "rag")),
            Map.entry("embedding", List.of("embedding", "embeddings")),
            Map.entry("embeddings", List.of("embedding", "embeddings")),
            Map.entry("quantization", List.of("quantization", "quantisation", "quantized")),
            Map.entry("diffusion", List.of("diffusion", "diffusion model", "diffusion models")),
            Map.entry("reinforcement learning", List.of("reinforcement learning")),
            Map.entry("gpu", List.of("gpu", "gpus")),
            Map.entry("gpus", List.of("gpu", "gpus")),
            Map.entry("api", List.of("api", "apis")),
            Map.entry("apis", List.of("api", "apis")),
            Map.entry("open weights", List.of("open weights", "open-weight", "open weight")),
            Map.entry("open-weight", List.of("open weights", "open-weight", "open weight")),
            Map.entry("context window", List.of("context window", "context windows")),
            Map.entry("token", List.of("token", "tokens")),
            Map.entry("tokens", List.of("token", "tokens"))
    );

    private static final Set<String> STRONG_MODALITY = Set.of(
            "ensures", "guarantees", "proves", "eliminates", "always", "never", "unprecedented"
    );

    private static final Set<String> FACTUAL_FIELDS = Set.of(
            "whatHappened", "whyItHappened", "importance", "usefulToReader", "recommendedAction",
            "technology", "howItWorks", "whereUsed", "relatedTechnologies", "technicalExplanation"
    );

    public ExplanationDraft validate(ExplanationDraft draft, Article article) {
        return validate(draft, article, ArticleSourceMaterial.fromFeed(article));
    }

    public ExplanationDraft validate(ExplanationDraft draft, Article article, ArticleSourceMaterial material) {
        if (draft == null || article == null) {
            return draft;
        }
        String source = sourceText(article, material);
        List<ExplanationDraft.Claim> claims = validateClaims(draft.claims(), source);

        return new ExplanationDraft(
                validateField(draft.whatHappened(), "whatHappened", source, claims),
                validateField(draft.technology(), "technology", source, claims),
                validateField(draft.importance(), "importance", source, claims),
                validateField(draft.howItWorks(), "howItWorks", source, claims),
                validateField(draft.whereUsed(), "whereUsed", source, claims),
                validateField(draft.fresherLearning(), "fresherLearning", source, claims),
                validateField(draft.practicalExample(), "practicalExample", source, claims),
                validateField(draft.relatedTechnologies(), "relatedTechnologies", source, claims),
                draft.sourceEvidence(),
                validateField(draft.whoBenefits(), "whoBenefits", source, claims),
                validateField(draft.useCases(), "useCases", source, claims),
                validateField(draft.technicalExplanation(), "technicalExplanation", source, claims),
                validateField(draft.whyItHappened(), "whyItHappened", source, claims),
                validateField(draft.usefulToReader(), "usefulToReader", source, claims),
                validateField(draft.recommendedAction(), "recommendedAction", source, claims),
                claims,
                draft.metadata()
        );
    }

    private String validateField(String value, String field, String source, List<ExplanationDraft.Claim> claims) {
        if (value == null || value.isBlank() || isPlaceholder(value) || isHonestFallback(field, value)) {
            return value;
        }
        boolean supportedSentences = sentences(value).stream()
                .allMatch(sentence -> sentenceSupported(sentence, field, source, claims));
        boolean unsupportedAnchor = hasUnsupportedAnchor(value, source);
        boolean unsupportedModality = FACTUAL_FIELDS.contains(field) && hasUnsupportedModality(value, source);
        if (!supportedSentences || unsupportedAnchor || unsupportedModality) {
            String reason = unsupportedModality ? "unsupported_modality"
                    : unsupportedAnchor ? "unsupported_factual_anchor" : "no_sentence_level_support";
            log.info("AI explanation section rejected field={} reason={}", field, reason);
            return NOT_AVAILABLE;
        }
        return value;
    }

    private boolean sentenceSupported(String sentence, String field, String source,
                                      List<ExplanationDraft.Claim> claims) {
        if (claimVerifier.hasContradictoryContext(source, sentence)) return false;
        boolean directEvidence = java.util.Arrays.stream(source.split("(?<=[.!?])\\s+"))
                .anyMatch(sourceSentence -> claimVerifier.supports(sourceSentence, sentence));
        if (directEvidence) return true;
        return claims.stream().filter(claim -> field.equals(claim.section()))
                .anyMatch(claim -> claimVerifier.supports(claim.evidence(), sentence));
    }

    private List<String> sentences(String value) {
        return java.util.Arrays.stream(value.split("(?<=[.!?])\\s+"))
                .map(String::trim).filter(sentence -> !sentence.isBlank()).toList();
    }

    private boolean isHonestFallback(String field, String value) {
        return switch (field) {
            case "whyItHappened" -> value.equals("The article does not say why this happened.");
            case "importance" -> value.equals("The available source does not explain why this update is important.");
            case "usefulToReader" -> value.equals("The available source does not describe a specific personal benefit.")
                    || value.equals("The article does not describe a specific personal benefit.");
            default -> false;
        };
    }

    private List<ExplanationDraft.Claim> validateClaims(List<ExplanationDraft.Claim> claims, String source) {
        if (claims == null || claims.isEmpty()) {
            return List.of();
        }
        List<ExplanationDraft.Claim> valid = new ArrayList<>();
        for (ExplanationDraft.Claim claim : claims) {
            String rejectionReason = null;
            if (claim == null) {
                rejectionReason = "empty_claim";
            } else if (claim.kind() == ExplanationDraft.Claim.Kind.INTERPRETATION) {
                rejectionReason = "interpretation_not_source_fact";
            } else if (claim.statement() == null || claim.statement().isBlank()
                    || claim.evidence() == null || claim.evidence().isBlank()) {
                rejectionReason = "missing_statement_or_evidence";
            } else if (hasUnsupportedAnchor(claim.statement(), source)) {
                rejectionReason = "unsupported_factual_anchor";
            } else if (!SourceTextMatcher.contains(source, claim.evidence())) {
                rejectionReason = "evidence_not_in_source";
            } else if (!claimVerifier.supports(claim.evidence(), claim.statement())) {
                rejectionReason = "evidence_does_not_entail_statement";
            } else if (claimVerifier.hasContradictoryContext(source, claim.statement())) {
                rejectionReason = "contradictory_source_context";
            }
            if (rejectionReason != null) {
                log.info("AI explanation claim rejected section={} reason={}",
                        claim == null || claim.section() == null ? "unspecified" : claim.section(), rejectionReason);
                continue;
            }
            valid.add(new ExplanationDraft.Claim(claim.section(), ExplanationDraft.Claim.Kind.SOURCE_FACT,
                    claim.statement(), claim.evidence()));
        }
        return valid;
    }

    private boolean hasUnsupportedAnchor(String value, String source) {
        Matcher numbers = NUMBER.matcher(value);
        while (numbers.find()) {
            String token = trimNumber(numbers.group());
            if (!token.isBlank() && !source.contains(token)) {
                return true;
            }
        }

        Matcher capitalized = CAPITALIZED_WORD.matcher(value);
        while (capitalized.find()) {
            String word = capitalized.group();
            String lower = word.toLowerCase(Locale.ROOT);
            if (NON_ANCHOR_CAPITALIZED.contains(lower)) {
                continue;
            }
            if (!source.contains(lower)) {
                return true;
            }
        }

        String lower = value.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> entry : TECHNICAL_TERMS.entrySet()) {
            if (containsPhrase(lower, entry.getKey())
                    && entry.getValue().stream().noneMatch(source::contains)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasUnsupportedModality(String value, String source) {
        String lower = value.toLowerCase(Locale.ROOT);
        for (String term : STRONG_MODALITY) {
            if (containsPhrase(lower, term) && !source.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsPhrase(String text, String phrase) {
        int from = 0;
        while (true) {
            int index = text.indexOf(phrase, from);
            if (index < 0) {
                return false;
            }
            int before = index - 1;
            int after = index + phrase.length();
            boolean leftBoundary = before < 0 || !Character.isLetterOrDigit(text.charAt(before));
            boolean rightBoundary = after >= text.length() || !Character.isLetterOrDigit(text.charAt(after));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            from = index + 1;
        }
    }

    private String trimNumber(String token) {
        int end = token.length();
        while (end > 0 && ".,%+-".indexOf(token.charAt(end - 1)) >= 0) {
            end--;
        }
        return token.substring(0, end);
    }

    private boolean isPlaceholder(String value) {
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("not available in the provided text")
                || lower.startsWith("not available in the supplied text");
    }

    private String sourceText(Article article, ArticleSourceMaterial material) {
        String title = article.getTitle() == null ? "" : article.getTitle();
        String source = material == null ? "" : material.text();
        return (title + ". " + source).toLowerCase(Locale.ROOT);
    }
}
