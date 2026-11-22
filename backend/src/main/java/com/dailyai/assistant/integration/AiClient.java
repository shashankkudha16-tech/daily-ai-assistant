package com.dailyai.assistant.integration;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.entity.Article;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional AI integration. Any OpenAI compatible chat completions endpoint works, so the
 * application is not tied to one vendor: only the base URL, model name and API key change.
 *
 * <p>Resilience rules:</p>
 * <ul>
 *   <li>the provider is skipped entirely when no key is configured or AI_ENABLED is false;</li>
 *   <li>HTTP 429 and 5xx responses are retried with a bounded exponential backoff, honouring
 *       a Retry-After header when the provider sends one;</li>
 *   <li>timeouts and network errors are retried and then swallowed;</li>
 *   <li>400/401/403 are treated as configuration errors and never retried;</li>
 *   <li>after repeated failures the client backs off for a cooldown window;</li>
 *   <li>a configurable daily budget caps provider requests so a retry storm cannot spend
 *       unbounded money.</li>
 * </ul>
 *
 * <p>Callers always fall back to the offline explanation built from the feed text.</p>
 */
@Component
public class AiClient implements ExplanationProvider {

    private static final Logger log = LoggerFactory.getLogger(AiClient.class);

    private static final String SYSTEM_PROMPT = """
            You are a careful teacher explaining an industry news item to a beginner.
            Rules:
            - Use everyday words a beginner can understand. Keep sentences short and the full
              explanation concise. Avoid jargon; when a technical term is needed, explain it
              briefly in technology or technicalExplanation.
            - whatHappened must be 2 or 3 simple sentences describing only the news.
            - importance must briefly say why the news may matter, without overstating impact.
            - practicalExample is optional: use it only when a simple example genuinely clarifies
              the article. Otherwise write exactly "Not available in the provided text."
            - technicalExplanation should briefly define difficult terms that appear in the source.
            - Only use the supplied article title and feed description as evidence.
            - Never invent product capabilities, release dates, pricing, benchmarks, API features,
              technical specifications or company announcements.
            - If the supplied text does not contain enough information for a field, write exactly:
              "Not available in the provided text."
            - Distinguish publisher facts from your interpretation: put interpretation in the
              sections that ask for it, and put publisher quotes only in sourceEvidence and claims.
            - Provide 1 to 5 claims. Each claim must be a short statement plus an exact quote
              copied from the supplied title or feed description that supports that claim.
              Do not use the same quote as proof for unrelated claims.
            - whoBenefits must describe audience groups, not a named reader or personal interests.
            - Do not add a publication date, price, benchmark, product name, organisation name,
              number or technical term that is not in the supplied text. Paraphrase only what
              the publisher actually wrote; if a detail is absent, use the unavailable placeholder.
            - Never strengthen the publisher's wording. Do not turn "with" into "ensures",
              "can" into "will", or a possibility into a guarantee.
            Return ONLY a JSON object with these keys:
            whatHappened, technology, importance, howItWorks, whereUsed, fresherLearning,
            practicalExample, relatedTechnologies, sourceEvidence, whoBenefits, useCases,
            technicalExplanation, claims.
            claims must be an array of objects with keys: section, kind, statement, evidence.
            section must be one of: whatHappened, technology, importance, howItWorks, whereUsed,
            fresherLearning, practicalExample, relatedTechnologies, whoBenefits, useCases,
            technicalExplanation. kind must be SOURCE_FACT for a direct publisher fact or
            INTERPRETATION for your own inference. evidence must be an exact quote from the
            supplied title or description. "sourceEvidence" must also be an exact quote.
            """;

    private static final String RESEARCH_SYSTEM_PROMPT = """
            You are explaining a research abstract to a beginner in simple, everyday English.
            Rules:
            - Keep sentences short and the full explanation concise. Explain any necessary
              technical term in plain words.
            - whatHappened must be 2 or 3 simple sentences stating the research question and
              headline result, if both are reported.
            - importance must briefly state why the reported result may matter.
            - practicalExample is optional: use one only when it genuinely helps explain the
              abstract; otherwise write exactly "Not available in the provided text."
            - Only use the supplied article title and abstract as evidence.
            - Treat the abstract as a report of a study, not as proof that a product exists or is ready.
            - whatHappened must state the research question and the headline result.
            - howItWorks must describe the study design, data, models or baselines only when the
              abstract states them.
            - importance must stay within the significance the abstract reports or clearly supports;
              do not invent commercial impact, adoption or production readiness.
            - technicalExplanation must state limitations or caveats that the abstract supports.
            - If the abstract does not contain a field, write exactly:
              "Not available in the provided text."
            - Never add a product name, organisation, number, benchmark, dataset or technical term
              that is not in the supplied text.
            - Never strengthen the publisher's wording. Keep uncertainty and study scope intact.
            - Provide 1 to 5 claims. Each claim needs: section, kind, statement, evidence.
              section is the narrative field it supports. kind is SOURCE_FACT for a direct
              abstract fact or INTERPRETATION for your own inference. evidence must be an exact
              quote copied from the title or abstract. sourceEvidence must also be an exact quote.
            Return ONLY a JSON object with these keys:
            whatHappened, technology, importance, howItWorks, whereUsed, fresherLearning,
            practicalExample, relatedTechnologies, sourceEvidence, whoBenefits, useCases,
            technicalExplanation, claims.
            """;

    private static final long MAX_BACKOFF_MS = 10_000;
    private static final int MAX_CLAIMS = 5;
    private static final java.util.Set<String> VALID_SECTIONS = java.util.Set.of(
            "whatHappened", "technology", "importance", "howItWorks", "whereUsed",
            "fresherLearning", "practicalExample", "relatedTechnologies", "whoBenefits",
            "useCases", "technicalExplanation");

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final AiUsageBudget usageBudget;
    private final NarrativeFactualityValidator factualityValidator;

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong cooldownUntil = new AtomicLong(0);

    public AiClient(AppProperties properties, ObjectMapper objectMapper, AiUsageBudget usageBudget,
                    NarrativeFactualityValidator factualityValidator) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.usageBudget = usageBudget;
        this.factualityValidator = factualityValidator;

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        Duration timeout = Duration.ofMillis(properties.getAi().getTimeoutMs());
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);

        this.restClient = RestClient.builder()
                .baseUrl(properties.getAi().getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getAi().getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public boolean isEnabled() {
        return properties.getAi().isEnabled();
    }

    @Override
    public boolean isAvailable() {
        return isEnabled() && !isCoolingDown() && hasBudget();
    }

    /** True while the client skips calls after repeated provider failures. */
    public boolean isCoolingDown() {
        return System.currentTimeMillis() < cooldownUntil.get();
    }

    /** Provider requests still allowed today, or -1 when the budget is unlimited. */
    public int remainingDailyCalls() {
        int max = properties.getAi().getMaxCallsPerDay();
        if (max <= 0) {
            return -1;
        }
        try {
            return Math.max(0, max - usageBudget.attempts(today()));
        } catch (RuntimeException ex) {
            log.warn("AI usage ledger unavailable; reporting no remaining calls: {}", ex.getMessage());
            return 0;
        }
    }

    public boolean hasBudget() {
        int remaining = remainingDailyCalls();
        return remaining != 0;
    }

    @Override
    public Optional<ExplanationDraft> generateExplanation(Article article) {
        if (!isEnabled()) {
            return Optional.empty();
        }
        if (isCoolingDown()) {
            log.debug("Skipping AI explanation for article {}: provider in cooldown", article.getId());
            return Optional.empty();
        }
        if (!hasBudget()) {
            log.warn("Skipping AI explanation for article {}: daily call budget reached", article.getId());
            return Optional.empty();
        }

        int maxAttempts = Math.max(1, properties.getAi().getMaxAttempts());
        int maxCalls = properties.getAi().getMaxCallsPerDay();
        boolean jsonMode = true;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            LocalDate attemptDate = today();
            if (!usageBudget.tryReserve(attemptDate, maxCalls)) {
                log.warn("AI daily call budget reached or usage ledger unavailable for article {}",
                        article.getId());
                break;
            }
            long startedAt = System.currentTimeMillis();
            try {
                ProviderResponse response = callProvider(article, jsonMode);
                long latency = System.currentTimeMillis() - startedAt;
                if (response.status() == 200) {
                    Optional<JsonNode> root = parseRoot(response.body());
                    if (root.isPresent()) {
                        Optional<ExplanationDraft> draft = buildDraft(root.get(), article, latency);
                        if (draft.isPresent()) {
                            log.info("AI explanation generated: provider={} model={} article={} "
                                            + "inputTokens={} outputTokens={} latencyMs={} outcome=SUCCESS",
                                    draft.get().metadata().provider(), draft.get().metadata().model(),
                                    article.getId(), draft.get().metadata().inputTokens(),
                                    draft.get().metadata().outputTokens(), latency);
                            recordSuccess(attemptDate, draft.get().metadata().inputTokens(),
                                    draft.get().metadata().outputTokens());
                            return draft;
                        }
                    }
                    // A parseable provider that returns unusable JSON is usually a model that
                    // does not support response_format, so retry once without JSON mode.
                    jsonMode = false;
                    if (attempt < maxAttempts) {
                        continue;
                    }
                    recordFailure("unusable JSON payload", article, attemptDate);
                } else if (response.status() == 429 || response.status() >= 500) {
                    log.warn("AI provider returned HTTP {} for article {} (attempt {}/{})",
                            response.status(), article.getId(), attempt, maxAttempts);
                    if (attempt < maxAttempts) {
                        sleep(backoffMs(attempt, response.retryAfter()));
                        continue;
                    }
                    recordFailure("HTTP " + response.status(), article, attemptDate);
                } else {
                    // 400/401/403 are configuration problems; retrying cannot help.
                    recordFailure("HTTP " + response.status(), article, attemptDate);
                    break;
                }
            } catch (ResourceAccessException ex) {
                long latency = System.currentTimeMillis() - startedAt;
                log.warn("AI provider unreachable for article {} after {} ms (attempt {}/{}): {}",
                        article.getId(), latency, attempt, maxAttempts, ex.getMessage());
                if (attempt < maxAttempts) {
                    sleep(backoffMs(attempt, null));
                    continue;
                }
                recordFailure("timeout or network error", article, attemptDate);
            } catch (Exception ex) {
                log.warn("AI explanation failed for article {}: {}", article.getId(), ex.getMessage());
                recordFailure(ex.getClass().getSimpleName(), article, attemptDate);
                break;
            }
        }
        return Optional.empty();
    }

    private Optional<ExplanationDraft> buildDraft(JsonNode root, Article article, long latency) {
        JsonNode content = parseContent(root);
        if (content == null) {
            return Optional.empty();
        }
        ExplanationDraft draft = factualityValidator.validate(toDraft(content, root, latency), article);
        if (!draft.isComplete() || !draft.hasSourceEvidence(article)
                || !draft.hasSufficientSubstance(ResearchArticleDetector.isResearch(article))) {
            return Optional.empty();
        }
        List<ExplanationDraft.Claim> supported = draft.supportedClaims(article);
        if (!draft.hasGroundedSourceFact(article)) {
            // Claim-level source evidence is required for AI text. Without it the
            // BASIC explanation is safer than an unsupported narrative.
            return Optional.empty();
        }
        int trimmed = Math.min(supported.size(), MAX_CLAIMS);
        return Optional.of(draft.withClaims(supported.subList(0, trimmed)));
    }

    private Optional<JsonNode> parseRoot(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readTree(raw));
        } catch (Exception ex) {
            log.debug("Could not parse AI provider envelope: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private JsonNode parseContent(JsonNode root) {
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(stripCodeFence(content));
        } catch (Exception ex) {
            log.debug("Could not parse AI response content: {}", ex.getMessage());
            return null;
        }
    }

    private ProviderResponse callProvider(Article article, boolean jsonMode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getAi().getModel());
        payload.put("temperature", 0.1);
        boolean research = ResearchArticleDetector.isResearch(article);
        payload.put("messages", List.of(
                Map.of("role", "system", "content", research ? RESEARCH_SYSTEM_PROMPT : SYSTEM_PROMPT),
                Map.of("role", "user", "content", buildUserPrompt(article, research))
        ));
        if (jsonMode) {
            payload.put("response_format", Map.of("type", "json_object"));
        }

        return restClient.post()
                .uri("/chat/completions")
                .body(payload)
                .exchange((request, response) -> new ProviderResponse(
                        response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8),
                        response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
    }

    private long backoffMs(int attempt, String retryAfter) {
        Long fromHeader = parseRetryAfter(retryAfter);
        if (fromHeader != null) {
            return Math.min(fromHeader, MAX_BACKOFF_MS);
        }
        long base = Math.max(0, properties.getAi().getRetryDelayMs());
        long delay = base * (1L << Math.min(attempt - 1, 4));
        return Math.min(delay, MAX_BACKOFF_MS);
    }

    private Long parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Math.max(0, Long.parseLong(value.trim()) * 1000);
        } catch (NumberFormatException ignored) {
            // Retry-After may also be an HTTP date; fall back to exponential backoff.
            return null;
        }
    }

    private void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private void recordSuccess(LocalDate usageDate, int inputTokens, int outputTokens) {
        consecutiveFailures.set(0);
        usageBudget.recordSuccess(usageDate, inputTokens, outputTokens);
    }

    private void recordFailure(String reason, Article article, LocalDate usageDate) {
        usageBudget.recordFailure(usageDate);
        int failures = consecutiveFailures.incrementAndGet();
        int threshold = Math.max(1, properties.getAi().getFailureThreshold());
        log.warn("AI explanation unavailable for article {} ({}); consecutive failures {}/{}",
                article.getId(), reason, failures, threshold);
        if (failures >= threshold) {
            cooldownUntil.set(System.currentTimeMillis() + Math.max(0, properties.getAi().getCooldownMs()));
            consecutiveFailures.set(0);
            log.warn("Pausing AI explanations for {} ms after repeated failures",
                    properties.getAi().getCooldownMs());
        }
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(properties.getTimezone()));
    }

    private String buildUserPrompt(Article article, boolean research) {
        String published = article.getPublishedAt() == null
                ? "not provided by the feed"
                : DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
                        article.getPublishedAt().atZone(ZoneId.of(properties.getTimezone())));
        return """
                Article title: %s
                Source: %s
                Category: %s
                Published: %s
                Original URL: %s
                User request type: %s
                Feed description: %s

                Write the result for a beginner, not a specialist. Follow these sections:
                What happened? (2-3 simple sentences in whatHappened)
                Why does it matter? (briefly in importance)
                Simple example (only when genuinely helpful; otherwise use the unavailable text)
                Key takeaway (one short sentence in fresherLearning or technicalExplanation,
                without adding a new fact)
                Important terms (brief plain-English definitions in technology and
                technicalExplanation; use the unavailable text when none are supported).

                Figure out a neutral recommendation input for a general reader. Do not tailor the
                explanation to a named person or to selected interests; the application computes
                the personal recommendation separately.
                """.formatted(
                article.getTitle(),
                article.getSource() == null ? "unknown" : article.getSource(),
                article.getCategory() == null ? "unknown" : article.getCategory().getName(),
                published,
                article.getUrl() == null ? "unknown" : article.getUrl(),
                research ? "research abstract" : "industry news item",
                article.getDescription() == null || article.getDescription().isBlank()
                        ? "(no description supplied by the feed)"
                        : article.getDescription());
    }

    private ExplanationDraft toDraft(JsonNode node, JsonNode root, long latency) {
        JsonNode usage = root.path("usage");
        ExplanationDraft.Metadata metadata = new ExplanationDraft.Metadata(
                properties.getAi().getProvider(),
                properties.getAi().getModel(),
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(0),
                latency);
        return new ExplanationDraft(
                text(node, "whatHappened"),
                text(node, "technology"),
                text(node, "importance"),
                text(node, "howItWorks"),
                text(node, "whereUsed"),
                text(node, "fresherLearning"),
                text(node, "practicalExample"),
                text(node, "relatedTechnologies"),
                text(node, "sourceEvidence"),
                text(node, "whoBenefits"),
                text(node, "useCases"),
                text(node, "technicalExplanation"),
                claims(node),
                metadata
        );
    }

    private List<ExplanationDraft.Claim> claims(JsonNode node) {
        JsonNode array = node.path("claims");
        if (!array.isArray()) {
            return List.of();
        }
        List<ExplanationDraft.Claim> claims = new ArrayList<>();
        for (JsonNode item : array) {
            String statement = text(item, "statement");
            String evidence = text(item, "evidence");
            String sectionValue = text(item, "section");
            String kindValue = text(item, "kind");
            if (sectionValue != null && !VALID_SECTIONS.contains(sectionValue)) {
                continue;
            }
            ExplanationDraft.Claim.Kind kind = parseKind(kindValue);
            if (kindValue != null && kind == null) {
                continue;
            }
            if (statement != null && evidence != null) {
                claims.add(new ExplanationDraft.Claim(sectionValue,
                        kind == null ? ExplanationDraft.Claim.Kind.SOURCE_FACT : kind,
                        statement, evidence));
            }
        }
        return claims;
    }

    private ExplanationDraft.Claim.Kind parseKind(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "SOURCE_FACT" -> ExplanationDraft.Claim.Kind.SOURCE_FACT;
            case "INTERPRETATION" -> ExplanationDraft.Claim.Kind.INTERPRETATION;
            default -> null;
        };
    }

    private String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private record ProviderResponse(int status, String body, String retryAfter) {
    }
}
