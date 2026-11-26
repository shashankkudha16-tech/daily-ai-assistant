package com.dailyai.assistant.integration;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.entity.Article;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

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
            Explain the supplied publisher source to a beginner in simple English. Return one complete JSON object only. Required non-empty strings: whatHappened, whyItHappened, importance, usefulToReader, recommendedAction, technology, howItWorks, whereUsed, fresherLearning, practicalExample, relatedTechnologies, sourceEvidence, whoBenefits, useCases, technicalExplanation. Also include claims: 1 to 5 objects with section, kind, statement, evidence. kind is SOURCE_FACT or INTERPRETATION; section is one of the 14 fields above except sourceEvidence. The five narrative fields are whatHappened, whyItHappened, importance, usefulToReader, recommendedAction; use one or two short sentences each and do not repeat facts. Unsupported answers must be exactly "Not available in the provided text." Keep technical details in separate fields. For every factual narrative claim, use a SOURCE_FACT claim whose statement and evidence are the same exact complete source sentence; sourceEvidence must also be an exact complete source sentence. A quote's presence does not excuse unsupported claims: preserve negation, uncertainty, numbers, dates, and qualifications. Keep each named product, model, person, and capability attached to the exact subject stated in the source; never transfer a capability between subjects. For whyItHappened, require an explicitly stated cause; a result or capability is not a cause. For usefulToReader, require a source-stated practical use or benefit. For recommendedAction, include an action only when the publisher explicitly instructs readers to take it; a product announcement, promotional invitation, or inferred next step is not advice. Do not invent facts, benefits, purposes, or actions. Never imply partial source text is complete. If the source cannot support an answer, use the exact fallback. Do not add fields, comments, or prose.
            """;

    private static final long MAX_BACKOFF_MS = 10_000;
    private static final int MAX_CLAIMS = 5;
    private static final int DEFAULT_MAX_COMPLETION_TOKENS = 4096;
    private static final List<String> SAFE_PROVIDER_PARAMETERS = List.of(
            "reasoning_format", "include_reasoning", "response_format", "max_completion_tokens", "model");
    private static final java.util.Set<String> VALID_SECTIONS = java.util.Set.of(
            "whatHappened", "whyItHappened", "importance", "usefulToReader", "recommendedAction",
            "technology", "howItWorks", "whereUsed",
            "fresherLearning", "practicalExample", "relatedTechnologies", "whoBenefits",
            "useCases", "technicalExplanation");
    private static final Set<String> REQUIRED_EXPLANATION_FIELDS = Set.of(
            "whatHappened", "technology", "importance", "howItWorks", "whereUsed", "fresherLearning",
            "practicalExample", "relatedTechnologies", "sourceEvidence", "whoBenefits", "useCases",
            "technicalExplanation", "whyItHappened", "usefulToReader", "recommendedAction", "claims");
    private static final List<String> EXPLANATION_SCHEMA_FIELDS = List.of(
            "whatHappened", "technology", "importance", "howItWorks", "whereUsed", "fresherLearning",
            "practicalExample", "relatedTechnologies", "sourceEvidence", "whoBenefits", "useCases",
            "technicalExplanation", "whyItHappened", "usefulToReader", "recommendedAction", "claims");

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final AiUsageBudget usageBudget;
    private final NarrativeFactualityValidator factualityValidator;
    private final Clock clock;
    private final LongConsumer retryWaiter;
    private final Semaphore providerPermit = new Semaphore(1);

    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong cooldownUntil = new AtomicLong(0);

    @Autowired
    public AiClient(AppProperties properties, ObjectMapper objectMapper, AiUsageBudget usageBudget,
                    NarrativeFactualityValidator factualityValidator) {
        this(properties, objectMapper, usageBudget, factualityValidator, Clock.systemUTC(), null);
    }

    AiClient(AppProperties properties, ObjectMapper objectMapper, AiUsageBudget usageBudget,
             NarrativeFactualityValidator factualityValidator, Clock clock, LongConsumer retryWaiter) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.usageBudget = usageBudget;
        this.factualityValidator = factualityValidator;
        this.clock = clock;
        this.retryWaiter = retryWaiter == null ? this::sleep : retryWaiter;

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
            log.warn("AI usage ledger unavailable; reporting no remaining calls: {}", ex.getClass().getSimpleName());
            return 0;
        }
    }

    public boolean hasBudget() {
        int remaining = remainingDailyCalls();
        return remaining != 0;
    }

    @Override
    public Optional<ExplanationDraft> generateExplanation(Article article) {
        return generateExplanation(article, ArticleSourceMaterial.fromFeed(article));
    }

    @Override
    public Optional<ExplanationDraft> generateExplanation(Article article, ArticleSourceMaterial source) {
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

        if (!providerPermit.tryAcquire()) {
            log.debug("Skipping AI explanation for article {}: another provider request is active", article.getId());
            return Optional.empty();
        }

        ArticleSourceMaterial promptSource = SourcePromptOptimizer.optimize(source,
                properties.getAi().getMaxSourceTokens());
        try {
            int maxAttempts = Math.max(1, properties.getAi().getMaxAttempts());
            int maxCalls = properties.getAi().getMaxCallsPerDay();
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            LocalDate attemptDate = today();
            if (!usageBudget.tryReserve(attemptDate, maxCalls)) {
                log.warn("AI daily call budget reached or usage ledger unavailable for article {}",
                        article.getId());
                break;
            }
            long startedAt = System.currentTimeMillis();
            try {
                int estimatedInputTokens = estimatePromptTokens(article, promptSource);
                log.info("AI request token estimate provider={} model={} article={} estimatedInputTokens={} sourceCompleteness={}",
                        properties.getAi().getProvider(), properties.getAi().getModel(), article.getId(),
                        estimatedInputTokens, promptSource.completeness());
                ProviderResponse response = callProvider(article, promptSource);
                long latency = System.currentTimeMillis() - startedAt;
                if (response.status() == 200) {
                    Optional<JsonNode> root = parseRoot(response.body());
                    if (root.isEmpty()) {
                        log.info("AI response diagnostic provider={} model={} article={} status=200 "
                                        + "parse=invalid_envelope schema=not_checked",
                                properties.getAi().getProvider(), properties.getAi().getModel(), article.getId());
                        recordFailure("invalid provider response envelope", article, attemptDate);
                        break;
                    }

                    ResponseDiagnostics diagnostics = diagnostics(root.get());
                    JsonNode choice = root.get().path("choices").path(0);
                    log.info("AI response diagnostic provider={} model={} article={} status=200 "
                                    + "finish={} estimatedInputTokens={} promptTokens={} completionTokens={} totalTokens={} reasoningPresent={} refusalPresent={}",
                            properties.getAi().getProvider(), properties.getAi().getModel(), article.getId(),
                            diagnostics.finishReason(), estimatedInputTokens, diagnostics.promptTokens(), diagnostics.completionTokens(),
                            diagnostics.promptTokens() + diagnostics.completionTokens(),
                            diagnostics.reasoningPresent(), diagnostics.refusalPresent());
                    if (diagnostics.finishReason().equals("length") || diagnostics.refusalPresent()
                            || diagnostics.finishReason().equals("content_filter")
                            || choice.path("message").path("status").asText("").equalsIgnoreCase("incomplete")) {
                        String reason = diagnostics.finishReason().equals("length")
                                ? "provider response truncated" : "provider refusal or incomplete response";
                        log.info("AI response validation article={} parse=rejected reason={}", article.getId(), reason);
                        recordFailure(reason, article, attemptDate);
                        break;
                    }

                    JsonNode content = parseContent(root.get());
                    if (content == null) {
                        log.info("AI response validation article={} parse=failed schema=failed", article.getId());
                        recordFailure("malformed or schema-invalid explanation JSON", article, attemptDate);
                        break;
                    }
                    log.info("AI response validation article={} parse=valid schema=valid claimsReceived={}",
                            article.getId(), content.path("claims").size());
                    Optional<ExplanationDraft> draft = buildDraft(content, root.get(), article, promptSource, latency);
                    if (draft.isEmpty()) {
                    log.info("AI response validation article={} parse=valid schema=valid grounding=failed outcome=REJECTED",
                                article.getId());
                        recordFailure("explanation failed source grounding or completeness validation", article, attemptDate);
                        break;
                    }
                    log.info("AI response validation article={} parse=valid schema=valid grounding=valid",
                            article.getId());
                    log.info("AI explanation generated: provider={} model={} article={} inputTokens={} "
                                    + "outputTokens={} latencyMs={} outcome=SUCCESS",
                            draft.get().metadata().provider(), draft.get().metadata().model(), article.getId(),
                            draft.get().metadata().inputTokens(), draft.get().metadata().outputTokens(), latency);
                    recordSuccess(attemptDate, draft.get().metadata().inputTokens(), draft.get().metadata().outputTokens());
                    return draft;
                } else if (response.status() == 429 || response.status() >= 500) {
                    log.warn("AI provider returned HTTP {} for article {} (attempt {}/{})",
                            response.status(), article.getId(), attempt, maxAttempts);
                    OptionalLong retryAfter = parseRetryAfter(response.retryAfter());
                    if (retryAfter.isPresent() && retryAfter.getAsLong() > 0
                            && (attempt >= maxAttempts || retryAfter.getAsLong() > MAX_BACKOFF_MS)) {
                        long deferredMs = retryAfter.getAsLong();
                        deferProviderRetry(deferredMs);
                        log.info("AI retry deferred status={} retryAfterMs={} article={}",
                                    response.status(), retryAfter.getAsLong(), article.getId());
                        recordFailure("provider retry deferred by Retry-After", article, attemptDate);
                        break;
                    }
                    if (attempt < maxAttempts) {
                        long delay = retryAfter.isPresent() && retryAfter.getAsLong() > 0
                                ? retryAfter.getAsLong() : backoffMs(attempt);
                        retryWaiter.accept(delay);
                        continue;
                    }
                    recordFailure("HTTP " + response.status(), article, attemptDate);
                } else {
                    // Non-transient client errors are not retried; classify them without logging body text.
                    ProviderErrorDiagnostics error = providerErrorDiagnostics(response.body());
                    log.info("AI provider error diagnostic status={} category={} code={} type={} parameter={} messageClass={}",
                            response.status(), error.category(), error.code(), error.type(), error.parameter(),
                            error.messageClass());
                    recordFailure("HTTP " + response.status(), article, attemptDate);
                    break;
                }
            } catch (ResourceAccessException ex) {
                long latency = System.currentTimeMillis() - startedAt;
                log.warn("AI provider unreachable for article {} after {} ms (attempt {}/{}): {}",
                        article.getId(), latency, attempt, maxAttempts, ex.getClass().getSimpleName());
                if (attempt < maxAttempts) {
                    sleep(backoffMs(attempt));
                    continue;
                }
                recordFailure("timeout or network error", article, attemptDate);
            } catch (Exception ex) {
                log.warn("AI explanation failed for article {}: {}", article.getId(), ex.getClass().getSimpleName());
                recordFailure(ex.getClass().getSimpleName(), article, attemptDate);
                break;
            }
            }
            return Optional.empty();
        } finally {
            providerPermit.release();
        }
    }

    private Optional<ExplanationDraft> buildDraft(JsonNode content, JsonNode root, Article article,
                                                  ArticleSourceMaterial source, long latency) {
        ExplanationDraft draft = factualityValidator.validate(toDraft(content, root, latency), article, source);
        List<ExplanationDraft.Claim> supported = draft.supportedClaims(article, source);
        log.info("AI grounding diagnostic article={} claimsReceived={} claimsAccepted={} claimsRejected={}",
                article.getId(), content.path("claims").size(), supported.size(),
                Math.max(0, content.path("claims").size() - supported.size()));
        if (!draft.isComplete() || !draft.hasSourceEvidence(article, source)
                || !draft.hasSufficientSubstance(ResearchArticleDetector.isResearch(article))) {
            log.info("AI draft rejected article={} complete={} sourceEvidence={} substance={} research={}",
                    article.getId(), draft.isComplete(), draft.hasSourceEvidence(article, source),
                    draft.hasSufficientSubstance(ResearchArticleDetector.isResearch(article)),
                    ResearchArticleDetector.isResearch(article));
            return Optional.empty();
        }
        if (!draft.hasGroundedSourceFact(article, source)) {
            log.info("AI draft rejected article={} reason=no_supported_source_claim supportedClaims={}",
                    article.getId(), supported.size());
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
            JsonNode root = objectMapper.readTree(raw);
            return root != null && root.isObject() ? Optional.of(root) : Optional.empty();
        } catch (Exception ex) {
            log.debug("Could not parse AI provider envelope: {}", ex.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    private JsonNode parseContent(JsonNode root) {
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        if (content.isBlank()) {
            return null;
        }
        String candidate = stripCodeFence(content);
        try {
            JsonNode parsed = objectMapper.readTree(candidate);
            if (hasExpectedSchema(parsed)) return parsed;
            if (parsed != null && parsed.isObject()) {
                log.debug("AI explanation schema validation failed: {}", schemaProblems(parsed));
            }
        } catch (Exception ignored) {
            // Some compatible providers wrap JSON in short prose. Only an
            // extracted object that passes the complete schema is considered.
        }
        for (String object : jsonObjects(candidate)) {
            try {
                JsonNode parsed = objectMapper.readTree(object);
                if (hasExpectedSchema(parsed)) return parsed;
            } catch (Exception ignored) {
                // Continue scanning; no unvalidated text is accepted.
            }
        }
        log.debug("AI explanation payload was malformed or failed schema validation: {}",
                schemaProblemsFromCandidate(candidate));
        return null;
    }

    private List<String> schemaProblemsFromCandidate(String candidate) {
        for (String object : jsonObjects(candidate)) {
            try {
                JsonNode node = objectMapper.readTree(object);
                if (node.isObject()) return schemaProblems(node);
            } catch (Exception ignored) {
                // Never log provider-generated text or parse exception snippets.
            }
        }
        return List.of("no_complete_json_object");
    }

    private List<String> jsonObjects(String text) {
        List<String> objects = new ArrayList<>();
        int start = -1, depth = 0;
        boolean inString = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (ch == '\\') escaped = true;
                else if (ch == '"') inString = false;
                continue;
            }
            if (ch == '"') inString = true;
            else if (ch == '{') {
                if (depth++ == 0) start = i;
            } else if (ch == '}' && depth > 0 && --depth == 0) {
                objects.add(text.substring(start, i + 1));
                start = -1;
            }
        }
        return objects;
    }

    private boolean hasExpectedSchema(JsonNode node) {
        return schemaProblems(node).isEmpty();
    }

    private List<String> schemaProblems(JsonNode node) {
        List<String> problems = new ArrayList<>();
        if (node == null || !node.isObject()) return List.of("top_level_not_object");
        java.util.Set<String> actualFields = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(actualFields::add);
        java.util.Set<String> missing = new java.util.HashSet<>(REQUIRED_EXPLANATION_FIELDS);
        missing.removeAll(actualFields);
        java.util.Set<String> unexpected = new java.util.HashSet<>(actualFields);
        unexpected.removeAll(REQUIRED_EXPLANATION_FIELDS);
        if (!missing.isEmpty()) problems.add("missing_fields:" + String.join(",", missing.stream().sorted().toList()));
        if (!unexpected.isEmpty()) problems.add("unexpected_fields:" + String.join(",", unexpected.stream().sorted().toList()));
        for (String field : List.of("whatHappened", "technology", "importance", "howItWorks", "whereUsed",
                "fresherLearning", "practicalExample", "relatedTechnologies", "sourceEvidence", "whoBenefits",
                "useCases", "technicalExplanation", "whyItHappened", "usefulToReader", "recommendedAction")) {
            if (!node.path(field).isTextual() || node.path(field).asText().isBlank())
                problems.add("invalid_string_field:" + field);
        }
        JsonNode claims = node.get("claims");
        if (claims == null || !claims.isArray() || claims.isEmpty() || claims.size() > MAX_CLAIMS) {
            problems.add("invalid_claim_count");
            return problems;
        }
        int claimIndex = 0;
        for (JsonNode claim : claims) {
            String label = "claim_" + claimIndex++;
            if (!claim.isObject() || claim.size() != 4) {
                problems.add("invalid_claim_shape:" + label);
                continue;
            }
            if (!claim.path("section").isTextual() || !VALID_SECTIONS.contains(claim.path("section").asText()))
                problems.add("invalid_claim_section:" + label);
            if (!claim.path("kind").isTextual()
                    || !Set.of("SOURCE_FACT", "INTERPRETATION").contains(claim.path("kind").asText()))
                problems.add("invalid_claim_kind:" + label);
            if (!claim.path("statement").isTextual() || claim.path("statement").asText().isBlank())
                problems.add("invalid_claim_statement:" + label);
            if (!claim.path("evidence").isTextual() || claim.path("evidence").asText().isBlank())
                problems.add("invalid_claim_evidence:" + label);
        }
        return problems;
    }

    private ResponseDiagnostics diagnostics(JsonNode root) {
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");
        JsonNode content = message.get("content");
        String rawFinish = choice.path("finish_reason").asText("");
        String finish = Set.of("stop", "length", "content_filter", "tool_calls", "function_call")
                .contains(rawFinish) ? rawFinish : rawFinish.isBlank() ? "unspecified" : "other";
        boolean refusal = message.path("refusal").isTextual()
                ? !message.path("refusal").asText().isBlank()
                : !message.path("refusal").isMissingNode() && !message.path("refusal").isNull();
        int promptTokens = root.path("usage").path("prompt_tokens").asInt(0);
        int completionTokens = root.path("usage").path("completion_tokens")
                .asInt(root.path("usage").path("output_tokens").asInt(0));
        return new ResponseDiagnostics(finish, content != null && content.isTextual() ? content.asText().length() : 0,
                promptTokens, completionTokens, message.path("reasoning").isTextual()
                && !message.path("reasoning").asText().isBlank(), refusal);
    }

    private ProviderResponse callProvider(Article article, ArticleSourceMaterial source) {
        Map<String, Object> payload = buildApplicationRequestPayload(article, source);
        return restClient.post()
                .uri("/chat/completions")
                .body(payload)
                .exchange((request, response) -> new ProviderResponse(
                        response.getStatusCode().value(),
                        new String(response.getBody().readAllBytes(), StandardCharsets.UTF_8),
                        response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)));
    }

    Map<String, Object> buildApplicationRequestPayload(Article article, ArticleSourceMaterial source) {
        return buildRequestPayload(List.of(
                Map.of("role", "system", "content", SYSTEM_PROMPT),
                Map.of("role", "user", "content", buildUserPrompt(article, source))
        ), responseFormat());
    }

    /** Shared payload builder used by the real request path and sanitized serialization tests. */
    Map<String, Object> buildRequestPayload(List<Map<String, String>> messages,
                                            Map<String, Object> responseFormat) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", properties.getAi().getModel());
        payload.put("temperature", 0.1);
        payload.put("max_completion_tokens", Math.max(256, Math.min(8192,
                properties.getAi().getMaxCompletionTokens() > 0
                        ? properties.getAi().getMaxCompletionTokens() : DEFAULT_MAX_COMPLETION_TOKENS)));
        payload.put("messages", messages);
        if (responseFormat != null) {
            payload.put("response_format", responseFormat);
            if (isGroqGptOss()) {
                // Groq's GPT-OSS endpoint does not support reasoning_format.
                payload.put("include_reasoning", false);
            }
        }
        return payload;
    }

    private Map<String, Object> responseFormat() {
        if (isGroqGptOss()) {
            return strictExplanationResponseFormat();
        }
        // Other providers keep JSON mode; local schema validation remains mandatory.
        return Map.of("type", "json_object");
    }

    static Map<String, Object> minimalStrictSchemaResponseFormat() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of("answer", Map.of("type", "string")),
                "required", List.of("answer"),
                "additionalProperties", false);
        return Map.of("type", "json_schema", "json_schema", Map.of(
                "name", "minimal_answer", "strict", true, "schema", schema));
    }

    static Map<String, Object> strictExplanationResponseFormat() {
        Map<String, Object> claimProperties = new LinkedHashMap<>();
        claimProperties.put("section", Map.of("type", "string", "enum", List.of(
                        "whatHappened", "whyItHappened", "importance", "usefulToReader", "recommendedAction",
                        "technology", "howItWorks", "whereUsed", "fresherLearning", "practicalExample",
                        "relatedTechnologies", "whoBenefits", "useCases", "technicalExplanation")));
        claimProperties.put("kind", Map.of("type", "string", "enum", List.of("SOURCE_FACT", "INTERPRETATION")));
        claimProperties.put("statement", Map.of("type", "string"));
        claimProperties.put("evidence", Map.of("type", "string"));
        Map<String, Object> claimSchema = Map.of(
                "type", "object", "properties", claimProperties,
                "required", List.of("section", "kind", "statement", "evidence"),
                "additionalProperties", false);

        Map<String, Object> fields = new LinkedHashMap<>();
        for (String field : EXPLANATION_SCHEMA_FIELDS) {
            fields.put(field, "claims".equals(field)
                    ? Map.of("type", "array", "items", claimSchema)
                    : Map.of("type", "string"));
        }
        Map<String, Object> schema = Map.of(
                "type", "object", "properties", fields,
                "required", List.copyOf(fields.keySet()),
                "additionalProperties", false);
        return Map.of("type", "json_schema", "json_schema", Map.of(
                "name", "article_explanation", "strict", true, "schema", schema));
    }

    private boolean isGroqGptOss() {
        String provider = properties.getAi().getProvider();
        String model = properties.getAi().getModel();
        return "groq".equalsIgnoreCase(provider) && Set.of("openai/gpt-oss-20b", "openai/gpt-oss-120b")
                .contains(model.toLowerCase(Locale.ROOT));
    }

    private long backoffMs(int attempt) {
        long base = Math.max(0, properties.getAi().getRetryDelayMs());
        long delay = base * (1L << Math.min(attempt - 1, 4));
        return Math.min(delay, MAX_BACKOFF_MS);
    }

    private OptionalLong parseRetryAfter(String value) {
        if (value == null || value.isBlank()) {
            return OptionalLong.empty();
        }
        try {
            long seconds = Long.parseLong(value.trim());
            if (seconds < 0) return OptionalLong.empty();
            return OptionalLong.of(seconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : seconds * 1000);
        } catch (NumberFormatException ignored) {
            try {
                Instant retryAt = ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                return OptionalLong.of(Math.max(0, Duration.between(clock.instant(), retryAt).toMillis()));
            } catch (DateTimeParseException | ArithmeticException invalidDate) {
                return OptionalLong.empty();
            }
        }
    }

    /** Return only an allow-listed error category; never log provider error text or request data. */
    private String providerErrorCategory(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.path("error");
            String code = error.path("code").asText("").toLowerCase(Locale.ROOT);
            String type = error.path("type").asText("").toLowerCase(Locale.ROOT);
            String message = error.path("message").asText("").toLowerCase(Locale.ROOT);
            if (code.contains("json_validate") || message.contains("failed to validate json"))
                return "json_generation_validation";
            if (message.contains("reasoning_format")) return "reasoning_mode_configuration";
            if (message.contains("response_format")) return "json_mode_configuration";
            if (message.contains("max_completion_tokens") || message.contains("context length"))
                return "token_or_context_limit";
            if (message.contains("model")) return "model_configuration";
            if (type.contains("authentication")) return "credential_configuration";
        } catch (Exception ignored) {
            // Error body is deliberately not surfaced in diagnostics.
        }
        return "unclassified_provider_error";
    }

    /** Extract only fixed categories and known parameter names; never return provider message text. */
    ProviderErrorDiagnostics providerErrorDiagnostics(String body) {
        try {
            JsonNode error = objectMapper.readTree(body).path("error");
            String code = safeIdentifier(error.path("code").asText(""));
            String type = safeIdentifier(error.path("type").asText(""));
            String message = error.path("message").asText("").toLowerCase(Locale.ROOT);
            String parameter = error.path("param").asText("");
            if (!SAFE_PROVIDER_PARAMETERS.contains(parameter)) {
                parameter = SAFE_PROVIDER_PARAMETERS.stream().filter(message::contains).findFirst().orElse("unknown");
            }
            return new ProviderErrorDiagnostics(providerErrorCategory(body), code, type,
                    SAFE_PROVIDER_PARAMETERS.contains(parameter) ? parameter : "unknown",
                    safeProviderMessageClass(code, message));
        } catch (Exception ignored) {
            return new ProviderErrorDiagnostics("unclassified_provider_error", "unknown", "unknown", "unknown",
                    "unknown");
        }
    }

    private String safeProviderMessageClass(String code, String message) {
        if (code.contains("json_validate") || message.contains("failed to validate json"))
            return "json_validation_failed";
        if (message.contains("generated json does not match the expected schema"))
            return "generated_json_schema_mismatch";
        if (message.contains("invalid json") || message.contains("valid json syntax"))
            return "generated_json_syntax_invalid";
        if (message.contains("unsupported parameter") || message.contains("unknown parameter"))
            return "unsupported_request_parameter";
        if (message.contains("context length") || message.contains("maximum context"))
            return "context_limit_exceeded";
        return "unclassified_provider_message";
    }

    private String safeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,64}") ? value : "unknown";
    }

    private void deferProviderRetry(long retryAfterMs) {
        long now = System.currentTimeMillis();
        long until = retryAfterMs > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + retryAfterMs;
        cooldownUntil.accumulateAndGet(until, Math::max);
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
            long now = System.currentTimeMillis();
            long delay = Math.max(0, properties.getAi().getCooldownMs());
            long until = delay > Long.MAX_VALUE - now ? Long.MAX_VALUE : now + delay;
            cooldownUntil.accumulateAndGet(until, Math::max);
            consecutiveFailures.set(0);
            log.warn("Pausing AI explanations for {} ms after repeated failures",
                    properties.getAi().getCooldownMs());
        }
    }

    private LocalDate today() {
        return LocalDate.now(ZoneId.of(properties.getTimezone()));
    }

    private String buildUserPrompt(Article article, ArticleSourceMaterial source) {
        String truncation = source != null
                && source.completeness() == ArticleSourceMaterial.Completeness.TRUNCATED
                ? "[Source is truncated at a sentence boundary; remaining source was omitted.]\n" : "";
        return """
                Title: %s
                Source: %s / %s
                %s%s
                """.formatted(
                article.getTitle(),
                source == null ? ArticleSourceMaterial.Type.TITLE_ONLY : source.type(),
                source == null ? ArticleSourceMaterial.Completeness.UNKNOWN : source.completeness(),
                truncation,
                source == null || source.text().isBlank() ? "(title only)" : source.text());
    }

    private int estimatePromptTokens(Article article, ArticleSourceMaterial source) {
        String prompt = SYSTEM_PROMPT + buildUserPrompt(article, source);
        return Math.max(1, (int) Math.ceil(prompt.length() / 4.0));
    }

    private ExplanationDraft toDraft(JsonNode node, JsonNode root, long latency) {
        JsonNode usage = root.path("usage");
        ExplanationDraft.Metadata metadata = new ExplanationDraft.Metadata(
                properties.getAi().getProvider(),
                properties.getAi().getModel(),
                usage.path("prompt_tokens").asInt(0),
                usage.path("completion_tokens").asInt(usage.path("output_tokens").asInt(0)),
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
                missing(text(node, "whyItHappened"), NarrativeFactualityValidator.NOT_AVAILABLE),
                missing(text(node, "usefulToReader"), NarrativeFactualityValidator.NOT_AVAILABLE),
                missing(text(node, "recommendedAction"), NarrativeFactualityValidator.NOT_AVAILABLE),
                claims(node),
                metadata
        );
    }

    private String missing(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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

    private record ResponseDiagnostics(String finishReason, int contentChars, int promptTokens, int completionTokens,
                                       boolean reasoningPresent, boolean refusalPresent) {}

    record ProviderErrorDiagnostics(String category, String code, String type, String parameter,
                                    String messageClass) {}
}
