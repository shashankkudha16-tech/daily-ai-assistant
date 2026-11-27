package com.dailyai.assistant.service;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.entity.AiRequestDiagnostic;
import com.dailyai.assistant.integration.AiDiagnosticsRecorder;
import com.dailyai.assistant.integration.AiUsageBudget;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

@Component
public class MentorAiClient {
    private static final Logger log = LoggerFactory.getLogger(MentorAiClient.class);
    public static final String PROMPT_VERSION = "mentor-v2";
    public record Evidence(String id, String kind, String text, String date) { }
    public record SuggestedAction(String title, String action, String rationale, String relatedSkill, String priority,
                                  int estimatedMinutes, List<String> evidenceIds) { }
    public record Result(List<SuggestedAction> recommendations, int inputTokens, int outputTokens) { }
    private record HttpResult(int status, String body) { }
    private record Usage(int prompt, int completion, int total) { }
    private record Validation(List<SuggestedAction> actions, int rejectedCount, ResponseRejected firstRejection) { }
    private enum Rejection { PROVIDER_FAILURE, INVALID_JSON, INVALID_SCHEMA, MISSING_FIELDS,
        UNSUPPORTED_CLAIM, CONTRADICTORY_CLAIM, INSUFFICIENT_EVIDENCE, INCOMPLETE_OUTPUT }

    private static final Pattern NEGATION = Pattern.compile("(?i)\\b(not|no|never|without|cannot|can't|won't|didn't|doesn't)\\b");
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,80}");
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;

    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final AiUsageBudget usageBudget;
    private final RestClient client;
    private final AiDiagnosticsRecorder diagnostics;

    @Autowired
    public MentorAiClient(AppProperties properties, ObjectMapper mapper, AiUsageBudget usageBudget,
                          AiDiagnosticsRecorder diagnostics) {
        this(properties, mapper, usageBudget, createClient(properties), diagnostics);
    }

    MentorAiClient(AppProperties properties, ObjectMapper mapper, AiUsageBudget usageBudget, RestClient client) {
        this(properties, mapper, usageBudget, client, null);
    }

    MentorAiClient(AppProperties properties, ObjectMapper mapper, AiUsageBudget usageBudget, RestClient client,
                   AiDiagnosticsRecorder diagnostics) {
        this.properties = properties;
        this.mapper = mapper;
        this.usageBudget = usageBudget;
        this.client = client;
        this.diagnostics = diagnostics;
    }

    private static RestClient createClient(AppProperties properties) {
        Duration timeout = Duration.ofMillis(Math.max(1000, properties.getAi().getTimeoutMs()));
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    public Optional<Result> recommend(List<Evidence> evidence, LocalDate usageDate) {
        AppProperties.Ai ai = properties.getAi();
        if (!ai.isEnabledConfigured()) return Optional.empty();
        if (!ai.isEnabled() || blank(ai.getBaseUrl()) || blank(ai.getModel())) {
            record(usageDate, null, null, null, new Usage(0, 0, 0), false, false,
                    "CONFIGURATION_MISSING", "CONFIGURATION_MISSING", null);
            return Optional.empty();
        }
        if (!usageBudget.tryReserve(usageDate, ai.getMaxCallsPerDay())) return Optional.empty();

        Integer status = null;
        String providerCode = null;
        String finishReason = null;
        Usage usage = new Usage(0, 0, 0);
        boolean jsonParsed = false;
        boolean schemaValid = false;
        String fieldResult = "NOT_ATTEMPTED";
        String factualityReason = null;
        try {
            String prompt = prompt(evidence);
            HttpResult response = send(ai, prompt);
            status = response.status();
            if (status != 200) {
                providerCode = providerErrorCode(response.body());
                usage = readUsageQuietly(response.body());
                usageBudget.recordFailure(usageDate, usage.prompt(), usage.completion());
                record(usageDate, status, providerCode, null, usage, false, false,
                        Rejection.PROVIDER_FAILURE.name(), "PROVIDER_FAILURE", null);
                return Optional.empty();
            }

            JsonNode root;
            try {
                root = mapper.readTree(response.body());
            } catch (JsonProcessingException ex) {
                throw new ResponseRejected(Rejection.INVALID_JSON, "PROVIDER_ENVELOPE_JSON_INVALID", null);
            }
            usage = readUsage(root);
            JsonNode choice = requiredChoice(root);
            finishReason = safeCode(choice.path("finish_reason").asText(null));
            if (!"stop".equals(finishReason)) {
                throw new ResponseRejected(Rejection.INCOMPLETE_OUTPUT, "FINISH_REASON_NOT_STOP", null);
            }
            JsonNode content = choice.path("message").path("content");
            if (!content.isTextual()) throw new ResponseRejected(Rejection.MISSING_FIELDS, "MISSING_CONTENT", null);
            JsonNode json;
            try {
                json = mapper.readTree(content.asText());
            } catch (JsonProcessingException ex) {
                throw new ResponseRejected(Rejection.INVALID_JSON, "CONTENT_JSON_INVALID", null);
            }
            jsonParsed = true;
            Validation validation = validate(json, evidence);
            List<SuggestedAction> actions = validation.actions();
            schemaValid = validation.firstRejection() == null || validation.firstRejection().schemaValid;
            fieldResult = validation.rejectedCount() == 0 ? "ALL_FIELDS_VALID" : "PARTIAL_ITEM_REJECTIONS";
            if (actions.isEmpty() && validation.firstRejection() != null) throw validation.firstRejection();
            usageBudget.recordSuccess(usageDate, usage.prompt(), usage.completion());
            record(usageDate, status, null, finishReason, usage, true, schemaValid,
                    validation.rejectedCount() > 0 ? "PARTIALLY_ACCEPTED" : actions.isEmpty() ? "ACCEPTED_EMPTY" : "ACCEPTED", fieldResult, null);
            return Optional.of(new Result(actions, usage.prompt(), usage.completion()));
        } catch (ResponseRejected ex) {
            fieldResult = ex.fieldCode;
            factualityReason = switch (ex.rejection) {
                case UNSUPPORTED_CLAIM -> "UNSUPPORTED_CLAIM";
                case CONTRADICTORY_CLAIM -> "CONTRADICTORY_CLAIM";
                case INSUFFICIENT_EVIDENCE -> "INSUFFICIENT_EVIDENCE";
                default -> null;
            };
            schemaValid = ex.schemaValid;
            usageBudget.recordFailure(usageDate, usage.prompt(), usage.completion());
            record(usageDate, status, providerCode, finishReason, usage, jsonParsed, schemaValid,
                    ex.rejection.name(), fieldResult, factualityReason);
            return Optional.empty();
        } catch (ResourceAccessException ex) {
            usageBudget.recordFailure(usageDate, usage.prompt(), usage.completion());
            record(usageDate, status, null, finishReason, usage, jsonParsed, schemaValid,
                    "PROVIDER_FAILURE", "TRANSPORT_OR_TIMEOUT", null);
            return Optional.empty();
        } catch (Exception ex) {
            usageBudget.recordFailure(usageDate, usage.prompt(), usage.completion());
            record(usageDate, status, providerCode, finishReason, usage, jsonParsed, schemaValid,
                    "CLIENT_FAILURE", "UNEXPECTED_CLIENT_ERROR", null);
            return Optional.empty();
        }
    }

    private HttpResult send(AppProperties.Ai ai, String prompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", ai.getModel());
        payload.put("temperature", 0.1);
        payload.put("max_completion_tokens", Math.max(512, Math.min(4096, ai.getMaxCompletionTokens())));
        payload.put("response_format", responseFormat());
        if ("groq".equalsIgnoreCase(ai.getProvider()) && ai.getModel().toLowerCase(Locale.ROOT).startsWith("openai/gpt-oss-")) {
            payload.put("include_reasoning", false);
        }
        payload.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        return client.post().uri(ai.getBaseUrl().replaceAll("/+$", "") + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON)
                .headers(headers -> headers.setBearerAuth(ai.getApiKey()))
                .body(payload)
                .exchange((request, result) -> {
                    byte[] buffer = result.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (buffer.length > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("provider response too large");
                    return new HttpResult(result.getStatusCode().value(), new String(buffer, StandardCharsets.UTF_8));
                });
    }

    private String prompt(List<Evidence> evidence) throws JsonProcessingException {
        return "You are a careful learning mentor. Context is structured evidence saved by this user. "
                + "Return only the required JSON object with actionType, expectedOutcome, rationale, relatedSkill, priority, estimatedMinutes, evidenceId. "
                + "Do not return a title, action prose, claims, benefits, or user-status statements; the application renders fixed action text. "
                + "Choose exactly one existing evidenceId. PRACTICE is allowed only for a recorded challenge or activity; REVIEW is allowed "
                + "only for an existing challenge, activity, or in-progress/completed roadmap topic. Pair PRACTICE with PRACTICE_RECORDED_TOPIC "
                + "and REVIEW with REVIEW_RECORDED_TOPIC. Copy rationale exactly from the cited item. Never infer skills, completion, progress, "
                + "mastery, ability, or benefits. Omit unsupported recommendations.\nEvidence JSON:\n"
                + mapper.writeValueAsString(evidence);
    }

    private JsonNode requiredChoice(JsonNode root) {
        if (root == null || !root.isObject()) throw new ResponseRejected(Rejection.INVALID_SCHEMA, "ROOT_NOT_OBJECT", null);
        JsonNode choices = root.get("choices");
        if (choices == null) throw new ResponseRejected(Rejection.MISSING_FIELDS, "MISSING_CHOICES", null);
        if (!choices.isArray() || choices.isEmpty() || !choices.get(0).isObject())
            throw new ResponseRejected(Rejection.INVALID_SCHEMA, "CHOICES_INVALID", null);
        JsonNode choice = choices.get(0);
        if (choice.get("finish_reason") == null) throw new ResponseRejected(Rejection.MISSING_FIELDS, "MISSING_FINISH_REASON", null);
        if (choice.get("message") == null || !choice.get("message").isObject())
            throw new ResponseRejected(Rejection.MISSING_FIELDS, "MISSING_MESSAGE", null);
        return choice;
    }

    private Validation validate(JsonNode root, List<Evidence> evidence) {
        if (root == null || !root.isObject()) throw new ResponseRejected(Rejection.INVALID_SCHEMA, "CONTENT_ROOT_NOT_OBJECT", null);
        JsonNode items = root.get("recommendations");
        if (items == null) throw new ResponseRejected(Rejection.MISSING_FIELDS, "MISSING_RECOMMENDATIONS", null);
        if (root.size() != 1 || !items.isArray() || items.size() > 3)
            throw new ResponseRejected(Rejection.INVALID_SCHEMA, "RECOMMENDATIONS_SHAPE_INVALID", null);
        Map<String, Evidence> known = new HashMap<>();
        evidence.forEach(item -> known.put(item.id(), item));
        List<SuggestedAction> validated = new ArrayList<>();
        int rejectedCount = 0;
        ResponseRejected firstRejection = null;
        for (int index = 0; index < items.size(); index++) {
            try {
                validated.add(validateRecommendation(items.get(index), index, evidence, known));
            } catch (ResponseRejected rejected) {
                rejectedCount++;
                if (firstRejection == null) firstRejection = rejected;
            }
        }
        return new Validation(List.copyOf(validated), rejectedCount, firstRejection);
    }

    private SuggestedAction validateRecommendation(JsonNode item, int index, List<Evidence> evidence,
                                                    Map<String, Evidence> known) {
            String prefix = "RECOMMENDATIONS_" + index + "_";
            if (!item.isObject()) throw new ResponseRejected(Rejection.INVALID_SCHEMA, prefix + "NOT_OBJECT", null);
            String[] required = {"actionType", "expectedOutcome", "rationale", "relatedSkill", "priority", "estimatedMinutes", "evidenceId"};
            for (String field : required) if (!item.has(field))
                throw new ResponseRejected(Rejection.MISSING_FIELDS, prefix + "MISSING_" + field.toUpperCase(Locale.ROOT), null);
            if (item.size() != required.length) throw new ResponseRejected(Rejection.INVALID_SCHEMA, prefix + "UNKNOWN_FIELD", null);
            String actionType = text(item, "actionType", 16, prefix).toUpperCase(Locale.ROOT);
            String expectedOutcome = text(item, "expectedOutcome", 32, prefix).toUpperCase(Locale.ROOT);
            String rationale = text(item, "rationale", 500, prefix);
            String relatedSkill = text(item, "relatedSkill", 100, prefix);
            String priority = text(item, "priority", 10, prefix).toUpperCase(Locale.ROOT);
            JsonNode minutesNode = item.get("estimatedMinutes");
            if (!minutesNode.isIntegralNumber()) throw new ResponseRejected(Rejection.INVALID_SCHEMA, prefix + "ESTIMATED_MINUTES_TYPE", null);
            int minutes = minutesNode.asInt(-1);
            JsonNode evidenceIdNode = item.get("evidenceId");
            if (!List.of("LOW", "MEDIUM", "HIGH").contains(priority) || minutes < 5 || minutes > 120
                    || !evidenceIdNode.isTextual())
                throw new ResponseRejected(Rejection.INVALID_SCHEMA, prefix + "VALUE_OUT_OF_RANGE", null);
            String evidenceId = evidenceIdNode.asText();
            Evidence source = known.get(evidenceId);
            if (source == null) throw new ResponseRejected(Rejection.INSUFFICIENT_EVIDENCE, prefix + "UNKNOWN_EVIDENCE_ID", "INSUFFICIENT_EVIDENCE");
            if (!List.of("PRACTICE", "REVIEW").contains(actionType)
                    || !List.of("PRACTICE_RECORDED_TOPIC", "REVIEW_RECORDED_TOPIC").contains(expectedOutcome))
                throw new ResponseRejected(Rejection.INVALID_SCHEMA, prefix + "ACTION_OR_OUTCOME_ENUM", null);
            if (!validMentorAction(actionType, expectedOutcome, source))
                throw new ResponseRejected(Rejection.INSUFFICIENT_EVIDENCE, prefix + "ACTION_NOT_SUPPORTED_BY_EVIDENCE", "INSUFFICIENT_EVIDENCE");
            if (!relatedSkill.isBlank() && !("SKILL".equals(source.kind()) && source.text().equalsIgnoreCase(relatedSkill)))
                throw new ResponseRejected(Rejection.INSUFFICIENT_EVIDENCE, prefix + "RELATED_SKILL_NOT_IN_CONTEXT", "INSUFFICIENT_EVIDENCE");
            boolean exactEvidenceQuote = containsNormalized(source.text(), rationale);
            if (!exactEvidenceQuote) {
                boolean contradiction = polarityConflict(source.text(), rationale);
                throw new ResponseRejected(contradiction ? Rejection.CONTRADICTORY_CLAIM : Rejection.UNSUPPORTED_CLAIM,
                        prefix + (contradiction ? "RATIONALE_CONTRADICTS_EVIDENCE" : "RATIONALE_NOT_EXACT_EVIDENCE"),
                        contradiction ? "CONTRADICTORY_CLAIM" : "UNSUPPORTED_CLAIM");
            }
            String topic = excerpt(source.text(), 90);
            String title = actionType.equals("PRACTICE") ? "Practice: " + topic : "Review: " + topic;
            String action = actionType.equals("PRACTICE")
                    ? "Complete a short practice exercise based on this saved record: " + topic + "."
                    : "Review this saved record and note one question: " + topic + ".";
            return new SuggestedAction(title, action, rationale, relatedSkill, priority, minutes, List.of(source.id()));
    }

    private boolean validMentorAction(String actionType, String outcome, Evidence source) {
        boolean eligibleKind = List.of("CHALLENGE", "JOURNAL", "SKILL", "TOPIC_STATUS").contains(source.kind());
        if (!eligibleKind) return false;
        if ("TOPIC_STATUS".equals(source.kind()) && !normalize(source.text()).contains("in progress")
                && !normalize(source.text()).contains("completed")) return false;
        return switch (actionType + ":" + outcome) {
            case "PRACTICE:PRACTICE_RECORDED_TOPIC" -> List.of("CHALLENGE", "JOURNAL", "SKILL").contains(source.kind())
                    || ("TOPIC_STATUS".equals(source.kind()) && normalize(source.text()).contains("in progress"));
            case "REVIEW:REVIEW_RECORDED_TOPIC" -> true;
            default -> false;
        };
    }

    private String text(JsonNode item, String name, int max, String prefix) {
        JsonNode value = item.get(name);
        if (value == null) throw new ResponseRejected(Rejection.MISSING_FIELDS, prefix + "MISSING_" + name.toUpperCase(Locale.ROOT), null);
        if (!value.isTextual()) throw new ResponseRejected(Rejection.INVALID_SCHEMA, prefix + name.toUpperCase(Locale.ROOT) + "_TYPE", null);
        String text = value.asText().trim();
        if (text.length() > max || text.isBlank() && !"relatedSkill".equals(name))
            throw new ResponseRejected(Rejection.INVALID_SCHEMA, prefix + name.toUpperCase(Locale.ROOT) + "_VALUE", null);
        return text;
    }

    private boolean containsNormalized(String source, String quote) {
        String normalizedSource = normalize(source);
        String normalizedQuote = normalize(quote);
        return normalizedQuote.length() >= 8 && normalizedSource.contains(normalizedQuote);
    }

    private boolean polarityConflict(String source, String claim) {
        return NEGATION.matcher(source).results().count() != NEGATION.matcher(claim).results().count();
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private String excerpt(String value, int max) {
        String normalized = value == null ? "" : value.trim().replaceAll("\\s+", " ");
        return normalized.substring(0, Math.min(max, normalized.length()));
    }

    private Usage readUsage(JsonNode root) {
        JsonNode usage = root.path("usage");
        int prompt = Math.max(0, usage.path("prompt_tokens").asInt(0));
        int completion = Math.max(0, usage.path("completion_tokens").asInt(0));
        int total = Math.max(0, usage.path("total_tokens").asInt(prompt + completion));
        return new Usage(prompt, completion, total);
    }

    private Usage readUsageQuietly(String body) {
        try { return readUsage(mapper.readTree(body)); }
        catch (Exception ignored) { return new Usage(0, 0, 0); }
    }

    private String providerErrorCode(String body) {
        try {
            JsonNode root = mapper.readTree(body);
            String code = root.path("error").path("code").asText(null);
            if (blank(code)) code = root.path("error").path("type").asText(null);
            return safeCode(code);
        } catch (Exception ignored) { return null; }
    }

    private String safeCode(String value) {
        return value != null && SAFE_CODE.matcher(value).matches() ? value : null;
    }

    private void record(LocalDate date, Integer status, String providerCode, String finish, Usage usage,
                        boolean json, boolean schema, String outcome, String field, String factuality) {
        if (diagnostics == null) return;
        AppProperties.Ai ai = properties.getAi();
        try {
            diagnostics.record(new AiDiagnosticsRecorder.Event(date, "MENTOR_REVIEW", ai.getProvider(), ai.getModel(), status,
                    providerCode, finish, usage.prompt(), usage.completion(), usage.total(), json, schema, outcome, field, factuality));
        } catch (RuntimeException ex) {
            log.warn("AI diagnostic persistence failed operation=MENTOR_REVIEW");
        }
    }

    private Map<String, Object> responseFormat() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("actionType", Map.of("type", "string", "enum", List.of("PRACTICE", "REVIEW")));
        properties.put("expectedOutcome", Map.of("type", "string", "enum", List.of("PRACTICE_RECORDED_TOPIC", "REVIEW_RECORDED_TOPIC")));
        properties.put("rationale", Map.of("type", "string"));
        properties.put("relatedSkill", Map.of("type", "string"));
        properties.put("priority", Map.of("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH")));
        properties.put("estimatedMinutes", Map.of("type", "integer"));
        properties.put("evidenceId", Map.of("type", "string"));
        Map<String, Object> item = Map.of("type", "object", "properties", properties,
                "required", List.copyOf(properties.keySet()), "additionalProperties", false);
        Map<String, Object> schema = Map.of("type", "object", "properties", Map.of("recommendations",
                        Map.of("type", "array", "items", item)), "required", List.of("recommendations"),
                "additionalProperties", false);
        return Map.of("type", "json_schema", "json_schema", Map.of("name", "mentor_recommendations_v2",
                "strict", true, "schema", schema));
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }

    private static final class ResponseRejected extends RuntimeException {
        private final Rejection rejection;
        private final String fieldCode;
        private final boolean schemaValid;
        private ResponseRejected(Rejection rejection, String fieldCode, String factualityCode) {
            super(factualityCode);
            this.rejection = rejection;
            this.fieldCode = fieldCode;
            this.schemaValid = factualityCode != null;
        }
    }
}
