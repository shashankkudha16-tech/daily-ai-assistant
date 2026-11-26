package com.dailyai.assistant.integration;

import java.text.Normalizer;
import java.util.Locale;
import java.util.List;
import java.util.Set;

/**
 * Conservative, local claim verifier. It accepts complete source sentences and a
 * small set of unambiguous grammar/format normalizations; it deliberately does not infer
 * entailment from word overlap. Unsupported claims fail closed.
 *
 * <p>This is not a general natural-language inference model. Broader paraphrase
 * support needs an independently evaluated entailment system.</p>
 */
final class SourceClaimVerifier {
    private static final Set<String> NEGATION = Set.of("not", "no", "never", "neither", "without", "n't");
    private static final Set<String> UNCERTAINTY = Set.of("may", "might", "could", "possibly", "potentially", "appears", "suggests");
    private static final Set<String> COMPARATORS = Set.of("more", "less", "faster", "slower", "better", "worse", "higher", "lower", "than", "as", "least", "most");
    private static final Set<String> QUALIFIERS = Set.of("only", "at least", "up to", "about", "approximately", "roughly", "some", "all", "most", "few");
    private static final Set<String> DATE_MARKERS = Set.of("today", "yesterday", "tomorrow", "january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december");

    boolean supports(String evidence, String claim) {
        String source = normalize(evidence);
        String candidate = normalize(claim);
        if (source.isBlank() || candidate.isBlank()) return false;
        return samePolarityAndCertainty(source, candidate)
                && preservesNumbersDatesAndComparisons(source, candidate)
                && source.equals(candidate);
    }

    boolean hasContradictoryContext(String fullSource, String claim) {
        String normalizedClaim = normalize(claim);
        if (normalizedClaim.isBlank()) return true;
        for (String sentence : fullSource.split("(?<=[.!?])\\s+")) {
            String normalizedSentence = normalize(sentence);
            if (normalizedSentence.contains(normalizedClaim)
                    && !supports(sentence, claim)) return true;
        }
        return false;
    }

    private boolean samePolarityAndCertainty(String source, String claim) {
        if (!tokens(source, NEGATION).equals(tokens(claim, NEGATION))) return false;
        if (!tokens(source, UNCERTAINTY).equals(tokens(claim, UNCERTAINTY))) return false;
        return true;
    }

    private boolean preservesNumbersDatesAndComparisons(String source, String claim) {
        return anchors(source).equals(anchors(claim));
    }

    private Set<String> anchors(String text) {
        java.util.TreeSet<String> anchors = new java.util.TreeSet<>();
        for (String token : text.split(" ")) {
            if (token.matches(".*\\d.*") || COMPARATORS.contains(token) || DATE_MARKERS.contains(token)
                    || QUALIFIERS.contains(token)) anchors.add(token);
        }
        for (String phrase : List.of("at least", "up to")) {
            if (text.contains(phrase)) anchors.add("phrase:" + phrase);
        }
        return anchors;
    }

    private Set<String> tokens(String text, Set<String> selected) {
        java.util.TreeSet<String> found = new java.util.TreeSet<>();
        for (String token : text.split(" ")) if (selected.contains(token)) found.add(token);
        return found;
    }

    private String normalize(String text) {
        if (text == null) return "";
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\bwon't\\b", "will not")
                .replaceAll("\\bcan't\\b", "cannot")
                .replaceAll("\\b([\\p{L}]+)n't\\b", "$1 not")
                .replaceAll("\\b([\\p{L}]+)n’t\\b", "$1 not")
                .replaceAll("[^\\p{L}\\p{N}']+", " ").trim().replaceAll("\\s+", " ");
    }
}
