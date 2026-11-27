package com.dailyai.assistant.service;

import com.dailyai.assistant.config.AppProperties;
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
import java.time.ZoneId;
import java.text.Normalizer;
import java.util.*;
import java.util.regex.Pattern;

/** AI draft generator. The service layer maps evidence IDs back to persisted entities. */
@Component
public class LearningPlanAiClient {
    private static final Logger log = LoggerFactory.getLogger(LearningPlanAiClient.class);
    public record Evidence(String id, String kind, String text, String relatedSkill) { }
    public record Task(String section, String actionType, String expectedOutcome, String rationale, String priority,
                       int estimatedMinutes, String evidenceId) { }
    public record Result(List<Task> tasks, int inputTokens, int outputTokens) { }
    private record Validation(List<Task> tasks, int rejectedCount, Rejected firstRejection) { }
    private record HttpResult(int status, String body) { }
    private record Usage(int prompt, int completion, int total) { }
    private enum Rejection { PROVIDER_FAILURE, INVALID_JSON, INVALID_SCHEMA, MISSING_FIELDS,
        UNSUPPORTED_CLAIM, CONTRADICTORY_CLAIM, INSUFFICIENT_EVIDENCE, INCOMPLETE_OUTPUT }
    private static final Pattern NEGATION = Pattern.compile("(?i)\\b(not|no|never|without|cannot|can't|won't|didn't|doesn't)\\b");
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,80}");
    private static final int MAX_RESPONSE_BYTES = 128 * 1024;

    private final AppProperties properties;
    private final ObjectMapper mapper;
    private final AiUsageBudget budget;
    private final AiDiagnosticsRecorder diagnostics;
    private final RestClient client;

    @Autowired
    public LearningPlanAiClient(AppProperties properties, ObjectMapper mapper, AiUsageBudget budget,
                                AiDiagnosticsRecorder diagnostics) {
        this(properties, mapper, budget, diagnostics, createClient(properties));
    }

    LearningPlanAiClient(AppProperties properties, ObjectMapper mapper, AiUsageBudget budget,
                         AiDiagnosticsRecorder diagnostics, RestClient client) {
        this.properties = properties;
        this.mapper = mapper;
        this.budget = budget;
        this.diagnostics = diagnostics;
        this.client = client;
    }

    private static RestClient createClient(AppProperties properties) {
        Duration timeout = Duration.ofMillis(Math.max(1000, properties.getAi().getTimeoutMs()));
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(timeout).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory).build();
    }

    public Optional<Result> generate(List<Evidence> evidence, LocalDate planDate) {
        AppProperties.Ai ai = properties.getAi();
        LocalDate usageDate = LocalDate.now(ZoneId.of(properties.getTimezone()));
        if (!ai.isEnabledConfigured()) return Optional.empty();
        if (!ai.isEnabled() || blank(ai.getBaseUrl()) || blank(ai.getModel())) {
            record(usageDate, null, null, null, new Usage(0,0,0), false, false,
                    "CONFIGURATION_MISSING", "CONFIGURATION_MISSING", null);
            return Optional.empty();
        }
        if (!budget.tryReserve(usageDate, ai.getMaxCallsPerDay())) return Optional.empty();
        Integer status = null;
        String providerCode = null;
        String finish = null;
        Usage usage = new Usage(0, 0, 0);
        boolean parsed = false;
        boolean schemaValid = false;
        String field = "NOT_ATTEMPTED";
        String factuality = null;
        try {
            HttpResult response = send(ai, prompt(evidence));
            status = response.status();
            if (status != 200) {
                providerCode = providerErrorCode(response.body());
                usage = readUsageQuietly(response.body());
                budget.recordFailure(usageDate, usage.prompt(), usage.completion());
                record(usageDate, status, providerCode, null, usage, false, false, "PROVIDER_FAILURE", "PROVIDER_FAILURE", null);
                return Optional.empty();
            }
            JsonNode root;
            try { root = mapper.readTree(response.body()); }
            catch (JsonProcessingException ex) { throw reject(Rejection.INVALID_JSON, "PROVIDER_ENVELOPE_JSON_INVALID"); }
            usage = usage(root);
            JsonNode choice = choice(root);
            finish = safeCode(choice.path("finish_reason").asText(null));
            if (!"stop".equals(finish)) throw reject(Rejection.INCOMPLETE_OUTPUT, "FINISH_REASON_NOT_STOP");
            JsonNode content = choice.path("message").path("content");
            if (!content.isTextual()) throw reject(Rejection.MISSING_FIELDS, "MISSING_CONTENT");
            JsonNode json;
            try { json = mapper.readTree(content.asText()); }
            catch (JsonProcessingException ex) { throw reject(Rejection.INVALID_JSON, "CONTENT_JSON_INVALID"); }
            parsed = true;
            Validation validation = validate(json, evidence);
            List<Task> tasks = validation.tasks();
            schemaValid = validation.firstRejection() == null || validation.firstRejection().schemaValid;
            field = validation.rejectedCount() == 0 ? "ALL_FIELDS_VALID" : "PARTIAL_ITEM_REJECTIONS";
            if (tasks.isEmpty() && validation.firstRejection() != null) throw validation.firstRejection();
            budget.recordSuccess(usageDate, usage.prompt(), usage.completion());
            record(usageDate, status, null, finish, usage, true, schemaValid,
                    validation.rejectedCount() == 0 ? (tasks.isEmpty() ? "ACCEPTED_EMPTY" : "ACCEPTED") : "PARTIALLY_ACCEPTED", field, null);
            return Optional.of(new Result(tasks, usage.prompt(), usage.completion()));
        } catch (Rejected ex) {
            field = ex.field;
            schemaValid = ex.schemaValid;
            factuality = switch (ex.reason) {
                case UNSUPPORTED_CLAIM -> "UNSUPPORTED_CLAIM";
                case CONTRADICTORY_CLAIM -> "CONTRADICTORY_CLAIM";
                case INSUFFICIENT_EVIDENCE -> "INSUFFICIENT_EVIDENCE";
                default -> null;
            };
            budget.recordFailure(usageDate, usage.prompt(), usage.completion());
            record(usageDate, status, providerCode, finish, usage, parsed, schemaValid, ex.reason.name(), field, factuality);
            return Optional.empty();
        } catch (ResourceAccessException ex) {
            budget.recordFailure(usageDate, usage.prompt(), usage.completion());
            record(usageDate, status, null, finish, usage, parsed, schemaValid, "PROVIDER_FAILURE", "TRANSPORT_OR_TIMEOUT", null);
            return Optional.empty();
        } catch (Exception ex) {
            budget.recordFailure(usageDate, usage.prompt(), usage.completion());
            record(usageDate, status, providerCode, finish, usage, parsed, schemaValid, "CLIENT_FAILURE", "UNEXPECTED_CLIENT_ERROR", null);
            return Optional.empty();
        }
    }

    private HttpResult send(AppProperties.Ai ai, String prompt) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", ai.getModel());
        payload.put("temperature", 0.1);
        payload.put("max_completion_tokens", Math.max(512, Math.min(4096, ai.getMaxCompletionTokens())));
        payload.put("response_format", responseFormat());
        if ("groq".equalsIgnoreCase(ai.getProvider()) && ai.getModel().toLowerCase(Locale.ROOT).startsWith("openai/gpt-oss-"))
            payload.put("include_reasoning", false);
        payload.put("messages", List.of(Map.of("role", "user", "content", prompt)));
        return client.post().uri(ai.getBaseUrl().replaceAll("/+$", "") + "/chat/completions")
                .contentType(MediaType.APPLICATION_JSON).headers(h -> h.setBearerAuth(ai.getApiKey())).body(payload)
                .exchange((request, response) -> {
                    byte[] bytes = response.getBody().readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (bytes.length > MAX_RESPONSE_BYTES) throw new IllegalArgumentException("provider response too large");
                    return new HttpResult(response.getStatusCode().value(), new String(bytes, StandardCharsets.UTF_8));
                });
    }

    private String prompt(List<Evidence> evidence) throws JsonProcessingException {
        return "Select at most one supported task for each section: LEARN, PRACTICE, STAY_UPDATED. "
                + "Return only section, actionType, expectedOutcome, rationale, priority, estimatedMinutes, evidenceId. "
                + "Do not generate titles, descriptions, claims, benefits, progress, or free-form advice. The application renders fixed wording. "
                + "LEARN uses a non-completed career-relevant roadmap topic; PRACTICE uses a recorded difficulty or in-progress topic; "
                + "STAY_UPDATED uses an actual recent article. Use exactly one evidenceId and copy rationale exactly from that evidence. "
                + "Omit sections without eligible evidence. These are suggestions, never claims about the learner.\nEvidence JSON:\n"
                + mapper.writeValueAsString(evidence);
    }

    private JsonNode choice(JsonNode root) {
        if (root == null || !root.isObject()) throw reject(Rejection.INVALID_SCHEMA, "ROOT_NOT_OBJECT");
        JsonNode choices = root.get("choices");
        if (choices == null) throw reject(Rejection.MISSING_FIELDS, "MISSING_CHOICES");
        if (!choices.isArray() || choices.isEmpty() || !choices.get(0).isObject()) throw reject(Rejection.INVALID_SCHEMA, "CHOICES_INVALID");
        JsonNode choice = choices.get(0);
        if (!choice.has("finish_reason")) throw reject(Rejection.MISSING_FIELDS, "MISSING_FINISH_REASON");
        if (!choice.path("message").isObject()) throw reject(Rejection.MISSING_FIELDS, "MISSING_MESSAGE");
        return choice;
    }

    private Validation validate(JsonNode root, List<Evidence> evidence) {
        if (root == null || !root.isObject()) throw reject(Rejection.INVALID_SCHEMA, "CONTENT_ROOT_NOT_OBJECT");
        JsonNode tasks = root.get("tasks");
        if (tasks == null) throw reject(Rejection.MISSING_FIELDS, "MISSING_TASKS");
        if (root.size() != 1 || !tasks.isArray() || tasks.size() > 3) throw reject(Rejection.INVALID_SCHEMA, "TASKS_SHAPE_INVALID");
        Map<String, Evidence> known = new HashMap<>(); evidence.forEach(item -> known.put(item.id(), item));
        Set<String> sections = new HashSet<>();
        List<Task> validated = new ArrayList<>();
        Rejected firstRejection = null;
        int rejectedCount = 0;
        for (int i = 0; i < tasks.size(); i++) {
            try {
                Task task = validateTask(tasks.get(i), i, known, sections);
                validated.add(task);
            } catch (Rejected rejected) {
                rejectedCount++;
                if (firstRejection == null) firstRejection = rejected;
            }
        }
        return new Validation(List.copyOf(validated), rejectedCount, firstRejection);
    }

    private Task validateTask(JsonNode node, int i, Map<String, Evidence> known, Set<String> sections) {
            String prefix = "TASKS_" + i + "_";
            if (!node.isObject()) throw reject(Rejection.INVALID_SCHEMA, prefix + "NOT_OBJECT");
            String[] fields = {"section", "actionType", "expectedOutcome", "rationale", "priority", "estimatedMinutes", "evidenceId"};
            for (String field : fields) if (!node.has(field)) throw reject(Rejection.MISSING_FIELDS, prefix + "MISSING_" + field.toUpperCase(Locale.ROOT));
            if (node.size() != fields.length) throw reject(Rejection.INVALID_SCHEMA, prefix + "UNKNOWN_FIELD");
            String section = string(node, "section", 20, prefix).toUpperCase(Locale.ROOT);
            String actionType = string(node, "actionType", 32, prefix).toUpperCase(Locale.ROOT);
            String expectedOutcome = string(node, "expectedOutcome", 40, prefix).toUpperCase(Locale.ROOT);
            String rationale = string(node, "rationale", 500, prefix);
            String priority = string(node, "priority", 10, prefix).toUpperCase(Locale.ROOT);
            String evidenceId = string(node, "evidenceId", 100, prefix);
            JsonNode minutesNode = node.get("estimatedMinutes");
            if (!minutesNode.isIntegralNumber()) throw reject(Rejection.INVALID_SCHEMA, prefix + "MINUTES_TYPE");
            int minutes = minutesNode.asInt(-1);
            if (!List.of("LEARN", "PRACTICE", "STAY_UPDATED").contains(section)
                    || !List.of("LOW", "MEDIUM", "HIGH").contains(priority) || minutes < 5 || minutes > 120
                    || sections.contains(section))
                throw reject(Rejection.INVALID_SCHEMA, prefix + "VALUE_OR_DUPLICATE_SECTION");
            Evidence source = known.get(evidenceId);
            if (source == null) throw reject(Rejection.INSUFFICIENT_EVIDENCE, prefix + "UNKNOWN_EVIDENCE", "INSUFFICIENT_EVIDENCE");
            if (!eligible(section, source.kind())) throw reject(Rejection.INSUFFICIENT_EVIDENCE, prefix + "EVIDENCE_KIND_NOT_ALLOWED", "INSUFFICIENT_EVIDENCE");
            if (!List.of("STUDY_TOPIC", "REVIEW_TOPIC", "PRACTICE_EXERCISE", "BUILD_EXAMPLE", "READ_ARTICLE", "NOTE_KEY_POINT").contains(actionType)
                    || !List.of("UNDERSTAND_ROADMAP_TOPIC", "REVIEW_ROADMAP_TOPIC", "PRACTICE_RECORDED_DIFFICULTY", "BUILD_TOPIC_EXAMPLE", "READ_MATCHED_ARTICLE", "NOTE_ARTICLE_POINT").contains(expectedOutcome))
                throw reject(Rejection.INVALID_SCHEMA, prefix + "ACTION_OR_OUTCOME_ENUM");
            if (!validAction(section, actionType, expectedOutcome))
                throw reject(Rejection.INSUFFICIENT_EVIDENCE, prefix + "ACTION_OUTCOME_NOT_SUPPORTED", "INSUFFICIENT_EVIDENCE");
            if (!containsNormalized(source.text(), rationale)) {
                boolean contradiction = polarityConflict(source.text(), rationale);
                throw reject(contradiction ? Rejection.CONTRADICTORY_CLAIM : Rejection.UNSUPPORTED_CLAIM,
                        prefix + (contradiction ? "RATIONALE_CONTRADICTS_EVIDENCE" : "RATIONALE_NOT_EXACT_EVIDENCE"),
                        contradiction ? "CONTRADICTORY_CLAIM" : "UNSUPPORTED_CLAIM");
            }
            sections.add(section);
            return new Task(section, actionType, expectedOutcome, rationale, priority, minutes, evidenceId);
    }

    private boolean validAction(String section, String action, String outcome) {
        return switch (section + ":" + action + ":" + outcome) {
            case "LEARN:STUDY_TOPIC:UNDERSTAND_ROADMAP_TOPIC", "LEARN:REVIEW_TOPIC:REVIEW_ROADMAP_TOPIC",
                    "PRACTICE:PRACTICE_EXERCISE:PRACTICE_RECORDED_DIFFICULTY", "PRACTICE:BUILD_EXAMPLE:BUILD_TOPIC_EXAMPLE",
                    "STAY_UPDATED:READ_ARTICLE:READ_MATCHED_ARTICLE", "STAY_UPDATED:NOTE_KEY_POINT:NOTE_ARTICLE_POINT" -> true;
            default -> false;
        };
    }

    private boolean eligible(String section, String kind) {
        return switch (section) {
            case "LEARN" -> "ROADMAP_TOPIC".equals(kind);
            case "PRACTICE" -> "CHALLENGE".equals(kind) || "IN_PROGRESS_TOPIC".equals(kind);
            case "STAY_UPDATED" -> "RECENT_ARTICLE".equals(kind);
            default -> false;
        };
    }

    private String string(JsonNode item, String field, int max, String prefix) {
        JsonNode value = item.get(field);
        if (value == null) throw reject(Rejection.MISSING_FIELDS, prefix + "MISSING_" + field.toUpperCase(Locale.ROOT));
        if (!value.isTextual()) throw reject(Rejection.INVALID_SCHEMA, prefix + field.toUpperCase(Locale.ROOT) + "_TYPE");
        String text = value.asText().trim();
        if (text.isBlank() || text.length() > max) throw reject(Rejection.INVALID_SCHEMA, prefix + field.toUpperCase(Locale.ROOT) + "_VALUE");
        return text;
    }

    private boolean containsNormalized(String source, String quote) {
        String normalized = normalize(quote);
        return normalized.length() >= 8 && normalize(source).contains(normalized);
    }

    private boolean polarityConflict(String source, String claim) {
        return NEGATION.matcher(source).results().count() != NEGATION.matcher(claim).results().count();
    }

    private String normalize(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private Map<String, Object> responseFormat() {
        Map<String,Object> fields = new LinkedHashMap<>();
        fields.put("section", Map.of("type", "string", "enum", List.of("LEARN", "PRACTICE", "STAY_UPDATED")));
        fields.put("actionType", Map.of("type", "string", "enum", List.of("STUDY_TOPIC", "REVIEW_TOPIC", "PRACTICE_EXERCISE", "BUILD_EXAMPLE", "READ_ARTICLE", "NOTE_KEY_POINT")));
        fields.put("expectedOutcome", Map.of("type", "string", "enum", List.of("UNDERSTAND_ROADMAP_TOPIC", "REVIEW_ROADMAP_TOPIC", "PRACTICE_RECORDED_DIFFICULTY", "BUILD_TOPIC_EXAMPLE", "READ_MATCHED_ARTICLE", "NOTE_ARTICLE_POINT")));
        fields.put("rationale", Map.of("type", "string"));
        fields.put("priority", Map.of("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH")));
        fields.put("estimatedMinutes", Map.of("type", "integer")); fields.put("evidenceId", Map.of("type", "string"));
        Map<String,Object> task = Map.of("type", "object", "properties", fields,
                "required", List.copyOf(fields.keySet()), "additionalProperties", false);
        Map<String,Object> schema = Map.of("type", "object", "properties", Map.of("tasks", Map.of("type", "array", "items", task)),
                "required", List.of("tasks"), "additionalProperties", false);
        return Map.of("type", "json_schema", "json_schema", Map.of("name", "personal_learning_plan_v1", "strict", true, "schema", schema));
    }

    private Usage usage(JsonNode root) {
        int prompt = Math.max(0, root.path("usage").path("prompt_tokens").asInt(0));
        int completion = Math.max(0, root.path("usage").path("completion_tokens").asInt(0));
        return new Usage(prompt, completion, Math.max(0, root.path("usage").path("total_tokens").asInt(prompt + completion)));
    }

    private Usage readUsageQuietly(String body) { return usageQuietly(body); }
    private Usage usageQuietly(String body) { try { return usage(mapper.readTree(body)); } catch (Exception ex) { return new Usage(0,0,0); } }
    private String providerErrorCode(String body) {
        try { JsonNode error = mapper.readTree(body).path("error"); String code = error.path("code").asText(null);
            if (blank(code)) code = error.path("type").asText(null); return safeCode(code); }
        catch (Exception ex) { return null; }
    }
    private String safeCode(String value) { return value != null && SAFE_CODE.matcher(value).matches() ? value : null; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private Rejected reject(Rejection reason, String field) { return new Rejected(reason, field); }
    private Rejected reject(Rejection reason, String field, String factuality) { return new Rejected(reason, field, factuality); }

    private void record(LocalDate date, Integer status, String providerCode, String finish, Usage usage,
                        boolean parsed, boolean schema, String outcome, String field, String factuality) {
        try {
            diagnostics.record(new AiDiagnosticsRecorder.Event(date, "LEARNING_PLAN", properties.getAi().getProvider(),
                    properties.getAi().getModel(), status, providerCode, finish, usage.prompt(), usage.completion(), usage.total(),
                    parsed, schema, outcome, field, factuality));
        } catch (RuntimeException ex) {
            log.warn("AI diagnostic persistence failed operation=LEARNING_PLAN");
        }
    }

    private static final class Rejected extends RuntimeException {
        private final Rejection reason; private final String field; private final boolean schemaValid;
        private Rejected(Rejection reason, String field) { this.reason = reason; this.field = field; this.schemaValid = false; }
        private Rejected(Rejection reason, String field, String factuality) {
            this.reason = reason; this.field = field; this.schemaValid = factuality != null;
        }
    }
}
