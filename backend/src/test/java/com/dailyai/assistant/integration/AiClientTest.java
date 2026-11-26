package com.dailyai.assistant.integration;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.Category;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the real AiClient against a local mock provider that speaks the
 * OpenAI chat completions protocol, so no external API key or billable call is needed.
 */
class AiClientTest {

    private HttpServer server;
    private final List<StubResponse> responses = new ArrayList<>();
    private final List<String> requestBodies = new ArrayList<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile CountDownLatch requestStarted;
    private AppProperties properties;
    private InMemoryAiUsageBudget usageBudget;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", this::handle);
        server.setExecutor(null);
        server.start();

        properties = new AppProperties();
        properties.setTimezone("Asia/Kolkata");
        properties.getAi().setEnabled(true);
        properties.getAi().setProvider("mock");
        properties.getAi().setApiKey("test-key");
        properties.getAi().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getAi().setModel("mock-model");
        properties.getAi().setTimeoutMs(2000);
        properties.getAi().setMaxAttempts(2);
        properties.getAi().setRetryDelayMs(10);
        properties.getAi().setFailureThreshold(2);
        properties.getAi().setCooldownMs(60_000);
        properties.getAi().setMaxCallsPerDay(100);
        usageBudget = new InMemoryAiUsageBudget();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void springCreatesAiClientUsingItsProductionConstructor() {
        new ApplicationContextRunner()
                .withBean(AppProperties.class, AppProperties::new)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withBean(AiUsageBudget.class, InMemoryAiUsageBudget::new)
                .withBean(NarrativeFactualityValidator.class, NarrativeFactualityValidator::new)
                .withBean(AiClient.class)
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(AiClient.class));
    }

    @Test
    void withoutApiKeyTheProviderIsDisabledAndNeverCalled() {
        properties.getAi().setApiKey("");

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.isEnabled()).isFalse();
        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void generatesDraftWithSectionsClaimsAndUsageMetadata() throws Exception {
        assertThat(properties.getAi().getMaxCompletionTokens()).isEqualTo(4096);
        responses.add(StubResponse.json(200, completion(validJson())));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());
        Optional<ExplanationDraft> draft = client.generateExplanation(article());

        assertThat(draft).isPresent();
        assertThat(draft.get().whatHappened()).isEqualTo("The company says the new model ships today.");
        assertThat(draft.get().whoBenefits()).isEqualTo(NarrativeFactualityValidator.NOT_AVAILABLE);
        assertThat(draft.get().useCases()).isEqualTo(NarrativeFactualityValidator.NOT_AVAILABLE);
        assertThat(draft.get().technicalExplanation()).isEqualTo("The update is available for Android users.");
        assertThat(draft.get().claims()).hasSize(1);
        assertThat(draft.get().claims().get(0).evidence()).isEqualTo("The company says the new model ships today.");
        assertThat(draft.get().metadata().provider()).isEqualTo("mock");
        assertThat(draft.get().metadata().model()).isEqualTo("mock-model");
        assertThat(draft.get().metadata().inputTokens()).isEqualTo(120);
        assertThat(draft.get().metadata().outputTokens()).isEqualTo(80);
        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(new ObjectMapper().readTree(requestBodies.get(0)).path("response_format").path("type").asText())
                .isEqualTo("json_object");
        assertThat(new ObjectMapper().readTree(requestBodies.get(0)).path("max_completion_tokens").asInt())
                .isEqualTo(4096);
        var request = new ObjectMapper().readTree(requestBodies.get(0));
        assertThat(request.has("include_reasoning")).isFalse();
        assertThat(request.has("reasoning_format")).isFalse();
    }

    @Test
    void acceptsOnlyTheCompleteSchemaWhenProviderWrapsItInProse() {
        responses.add(StubResponse.json(200, completion("Here is the requested object:\n" + validJson() + "\nEnd.")));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isPresent();
    }

    @Test
    void acceptsFencedJsonOnlyAfterTheCompleteObjectPassesSchemaValidation() {
        responses.add(StubResponse.json(200, completion("```json\n" + validJson() + "\n```")));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isPresent();
    }

    @Test
    void usesDocumentedGroqGptOssReasoningControlWithJsonMode() throws Exception {
        properties.getAi().setProvider("groq");
        properties.getAi().setModel("openai/gpt-oss-20b");
        responses.add(StubResponse.json(200, completion(validJson())));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());
        assertThat(client.generateExplanation(article())).isPresent();

        var request = new ObjectMapper().readTree(requestBodies.get(0));
        assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(request.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        assertStrictSchemaCompatible(request.path("response_format").path("json_schema").path("schema"));
        assertThat(request.path("include_reasoning").isBoolean()).isTrue();
        assertThat(request.path("include_reasoning").asBoolean()).isFalse();
        assertThat(request.has("reasoning_format")).isFalse();
        assertThat(request.path("model").asText()).isEqualTo("openai/gpt-oss-20b");
        assertThat(request.path("max_completion_tokens").asInt()).isEqualTo(4096);
        assertThat(requestBodies.get(0).split("\"include_reasoning\"", -1)).hasSize(2);
        assertThat(requestBodies.get(0)).contains("\"include_reasoning\":false")
                .doesNotContain("reasoning_format", "\"include_reasoning\":null");
    }

    @Test
    void otherProvidersKeepJsonModeWithoutGroqReasoningParameters() throws Exception {
        responses.add(StubResponse.json(200, completion(validJson())));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());
        assertThat(client.generateExplanation(article())).isPresent();

        var request = new ObjectMapper().readTree(requestBodies.get(0));
        assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(request.has("include_reasoning")).isFalse();
        assertThat(request.has("reasoning_format")).isFalse();
    }

    @Test
    void serializesMinimalJsonObjectConfigurationWithoutNullOrSchemaFields() throws Exception {
        configureGroqGptOss();
        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget,
                new NarrativeFactualityValidator());
        List<java.util.Map<String, String>> messages = List.of(java.util.Map.of(
                "role", "user", "content", "Return only JSON: {\"answer\":\"short greeting\"}."));

        var payload = client.buildRequestPayload(messages, java.util.Map.of("type", "json_object"));
        String serialized = new ObjectMapper().writeValueAsString(payload);
        var request = new ObjectMapper().readTree(serialized);

        assertThat(request.path("model").asText()).isEqualTo("openai/gpt-oss-20b");
        assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
        assertThat(request.path("include_reasoning").isBoolean()).isTrue();
        assertThat(request.path("include_reasoning").asBoolean()).isFalse();
        assertThat(request.has("reasoning_format")).isFalse();
        assertThat(request.has("json_schema")).isFalse();
        assertThat(request.path("messages").path(0).path("role").asText()).isEqualTo("user");
        assertThat(request.path("messages").path(0).path("content").asText()).contains("JSON");
        assertThat(request.path("max_completion_tokens").asInt()).isEqualTo(4096);
        assertNoNullFields(request);
    }

    @Test
    void serializedApplicationRequestMatchesTheWireBodyCapturedByLocalProvider() throws Exception {
        configureGroqGptOss();
        Article candidate = article();
        ArticleSourceMaterial source = new ArticleSourceMaterial(
                ArticleSourceMaterial.Type.FULL_ARTICLE, ArticleSourceMaterial.Completeness.COMPLETE,
                "Synthetic local fixture: the article reports one test result.");
        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget,
                new NarrativeFactualityValidator());
        String expectedSerialized = new ObjectMapper().writeValueAsString(
                client.buildApplicationRequestPayload(candidate, source));
        responses.add(StubResponse.json(200, completion(validJson())));

        client.generateExplanation(candidate, source);

        var expected = new ObjectMapper().readTree(expectedSerialized);
        var actual = new ObjectMapper().readTree(requestBodies.get(0));
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.path("max_completion_tokens").asInt()).isEqualTo(4096);
        assertThat(actual.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(actual.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(actual.path("messages").path(0).path("role").asText()).isEqualTo("system");
        assertThat(actual.path("messages").path(0).path("content").asText()).contains("complete JSON object");
        assertThat(actual.path("messages").path(1).path("role").asText()).isEqualTo("user");
        assertThat(actual.path("messages").path(1).path("content").asText())
                .contains("Title:", "Source: FULL_ARTICLE / COMPLETE", "Synthetic local fixture");
        assertNoNullFields(actual);
    }

    @Test
    void serializesMinimalStrictJsonSchemaConfigurationWithGroqSupportedConstraints() throws Exception {
        configureGroqGptOss();
        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget,
                new NarrativeFactualityValidator());
        List<java.util.Map<String, String>> messages = List.of(java.util.Map.of(
                "role", "user", "content", "Return only JSON with one string field named answer."));

        var payload = client.buildRequestPayload(messages, AiClient.minimalStrictSchemaResponseFormat());
        String serialized = new ObjectMapper().writeValueAsString(payload);
        var request = new ObjectMapper().readTree(serialized);
        var schema = request.path("response_format").path("json_schema").path("schema");

        assertThat(request.path("model").asText()).isEqualTo("openai/gpt-oss-20b");
        assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_schema");
        assertThat(request.path("response_format").path("json_schema").path("strict").asBoolean()).isTrue();
        assertThat(request.path("include_reasoning").asBoolean()).isFalse();
        assertThat(request.has("reasoning_format")).isFalse();
        assertThat(request.path("max_completion_tokens").asInt()).isEqualTo(4096);
        assertStrictSchemaCompatible(schema);
        assertNoNullFields(request);
    }

    @Test
    void completeExplanationSchemaCoversContractUsingStrictCompatibleStructure() throws Exception {
        var responseFormat = new ObjectMapper().valueToTree(AiClient.strictExplanationResponseFormat());
        var schema = responseFormat.path("json_schema").path("schema");

        assertStrictSchemaCompatible(schema);
        assertThat(fieldNames(schema.path("properties"))).containsExactlyInAnyOrderElementsOf(java.util.Set.of(
                        "whatHappened", "whyItHappened", "importance", "usefulToReader", "recommendedAction",
                        "technology", "howItWorks", "whereUsed", "fresherLearning", "practicalExample",
                        "relatedTechnologies", "sourceEvidence", "whoBenefits", "useCases",
                        "technicalExplanation", "claims"));
        assertThat(fieldNames(schema.path("properties").path("claims").path("items").path("properties")))
                .containsExactlyInAnyOrder("section", "kind", "statement", "evidence");
        assertThat(schema.path("properties").path("claims").path("items").path("required").toString())
                .isEqualTo("[\"section\",\"kind\",\"statement\",\"evidence\"]");
        assertThat(schema.path("properties").path("claims").path("minItems").isMissingNode()).isTrue();
        assertThat(schema.path("properties").path("claims").path("maxItems").isMissingNode()).isTrue();
        assertOnlySupportedSchemaKeywords(schema);
    }

    private void configureGroqGptOss() {
        properties.getAi().setProvider("groq");
        properties.getAi().setModel("openai/gpt-oss-20b");
    }

    private void assertNoNullFields(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                assertThat(entry.getValue().isNull()).as("field %s is not null", entry.getKey()).isFalse();
                assertNoNullFields(entry.getValue());
            });
        } else if (node.isArray()) {
            node.forEach(this::assertNoNullFields);
        }
    }

    private void assertStrictSchemaCompatible(com.fasterxml.jackson.databind.JsonNode schema) {
        if (schema.path("type").asText().equals("object")) {
            var propertiesNode = schema.path("properties");
            assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
            java.util.Set<String> propertyNames = new java.util.HashSet<>();
            propertiesNode.fieldNames().forEachRemaining(propertyNames::add);
            java.util.Set<String> requiredNames = new java.util.HashSet<>();
            schema.path("required").forEach(value -> requiredNames.add(value.asText()));
            assertThat(requiredNames).containsExactlyInAnyOrderElementsOf(propertyNames);
            propertiesNode.elements().forEachRemaining(this::assertStrictSchemaCompatible);
        } else if (schema.path("type").asText().equals("array")) {
            assertStrictSchemaCompatible(schema.path("items"));
        }
    }

    private void assertOnlySupportedSchemaKeywords(com.fasterxml.jackson.databind.JsonNode node) {
        java.util.Set<String> allowed = java.util.Set.of(
                "type", "properties", "required", "additionalProperties", "items", "enum");
        if (node.isObject()) {
            java.util.Set<String> actual = new java.util.HashSet<>();
            node.fieldNames().forEachRemaining(actual::add);
            assertThat(allowed).containsAll(actual);
            var properties = node.path("properties");
            if (properties.isObject()) {
                properties.elements().forEachRemaining(this::assertOnlySupportedSchemaKeywords);
            }
            var items = node.path("items");
            if (items.isObject()) {
                assertOnlySupportedSchemaKeywords(items);
            }
        }
    }

    private java.util.Set<String> fieldNames(com.fasterxml.jackson.databind.JsonNode object) {
        java.util.Set<String> names = new java.util.HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    @Test
    void extractsOnlySafe400ProviderErrorMetadata() {
        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget,
                new NarrativeFactualityValidator());
        String body = """
                {"error":{"message":"Unsupported parameter reasoning_format; private detail: sentinel-value",
                 "type":"invalid_request_error","param":"reasoning_format","code":"unsupported_parameter"}}
                """;

        AiClient.ProviderErrorDiagnostics diagnostics = client.providerErrorDiagnostics(body);

        assertThat(diagnostics.category()).isEqualTo("reasoning_mode_configuration");
        assertThat(diagnostics.code()).isEqualTo("unsupported_parameter");
        assertThat(diagnostics.type()).isEqualTo("invalid_request_error");
        assertThat(diagnostics.parameter()).isEqualTo("reasoning_format");
        assertThat(diagnostics.messageClass()).isEqualTo("unsupported_request_parameter");
        assertThat(diagnostics.toString()).doesNotContain("private detail", "sentinel-value");
    }

    @Test
    void retriesRateLimitThenSucceeds() {
        responses.add(StubResponse.plain(429, "{\"error\":\"rate limited\"}", "0"));
        responses.add(StubResponse.json(200, completion(validJson())));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());
        Optional<ExplanationDraft> draft = client.generateExplanation(article());

        assertThat(draft).isPresent();
        assertThat(requestCount.get()).isEqualTo(2);
        assertThat(usageBudget.reservedToday()).isEqualTo(2);
    }

    @Test
    void rejectsValidJsonWhenAnyRequiredSectionIsMissing() {
        properties.getAi().setMaxAttempts(1);
        responses.add(StubResponse.json(200, completion(withoutField(validJson(), "whoBenefits"))));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void rejectsWrongFieldTypesWithoutRetryingTheSamePrompt() {
        responses.add(StubResponse.json(200, completion(validJson().replace(
                "\"importance\":\"It supports offline use.\"", "\"importance\":42"))));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(usageBudget.reservedToday()).isEqualTo(1);
    }

    @Test
    void rejectsBlankRequiredFieldsInsteadOfManufacturingAnExplanation() {
        responses.add(StubResponse.json(200, completion(validJson().replace(
                "\"importance\":\"It supports offline use.\"", "\"importance\":\"\""))));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidClaimKindAndUnexpectedTopLevelField() {
        String invalidKind = validJson().replace("\"kind\":\"SOURCE_FACT\"", "\"kind\":\"GUESS\"");
        String unexpectedField = invalidKind.substring(0, invalidKind.length() - 1) + ",\"extra\":\"text\"}";
        responses.add(StubResponse.json(200, completion(unexpectedField)));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void rejectsLengthFinishedOutputEvenWhenItsPartialTextLooksLikeJson() {
        responses.add(StubResponse.json(200, completion(validJson(), "length", null)));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void rejectsProviderRefusalEvenWhenItAlsoContainsJson() {
        responses.add(StubResponse.json(200, completion(validJson(), "stop", "refusal")));
        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void rejectsExplicitIncompleteMessageStatus() {
        responses.add(StubResponse.json(200, completion(validJson(), "stop", null, "incomplete")));
        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void rejectsUnexpectedProviderEnvelopeWithoutRetry() {
        responses.add(StubResponse.json(200, "{\"result\":\"not a chat completion\"}"));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void rejectsExplanationWithoutQuotedSourceEvidence() {
        properties.getAi().setMaxAttempts(1);
        responses.add(StubResponse.json(200, completion(validJson().replace(
                "\"sourceEvidence\":\"The company says the new model ships today.\"",
                "\"sourceEvidence\":\"A fabricated launch date\""))));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
    }

    @Test
    void dropsUnsupportedClaimsInsteadOfTreatingOneQuoteAsProof() {
        String json = withUnsupportedClaim(validJson());
        responses.add(StubResponse.json(200, completion(json)));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());
        ExplanationDraft draft = client.generateExplanation(article()).orElseThrow();

        assertThat(draft.claims()).hasSize(1);
        assertThat(draft.claims().get(0).statement()).isEqualTo("The company says the new model ships today.");
    }

    @Test
    void rejectsDraftWhenNoClaimCanBeGrounded() {
        properties.getAi().setMaxAttempts(1);
        responses.add(StubResponse.json(200, completion(validJson().replace(
                "\"evidence\":\"The company says the new model ships today.\"",
                "\"evidence\":\"an invented quote\""))));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
    }

    @Test
    void malformedJsonFailsClosedWithoutAnUnnecessaryDuplicateRequest() {
        responses.add(StubResponse.json(200, completion("I cannot answer that.")));
        responses.add(StubResponse.json(200, completion("Still not JSON.")));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(usageBudget.reservedToday()).isEqualTo(1);
    }

    @Test
    void badRequestIsNotRetried() {
        responses.add(StubResponse.plain(400, "{\"error\":\"bad request\"}", null));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void unauthorizedIsNotRetried() {
        responses.add(StubResponse.plain(401, "{\"error\":\"invalid key\"}", null));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void forbiddenIsNotRetried() {
        responses.add(StubResponse.plain(403, "{\"error\":\"forbidden\"}", null));
        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());
        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(usageBudget.reservedToday()).isEqualTo(1);
    }

    @Test
    void honorsHttpDateRetryAfterBeforeRetrying() {
        Instant now = Instant.parse("2026-01-02T03:04:05Z");
        String retryAt = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                ZonedDateTime.ofInstant(now.plusSeconds(2), ZoneOffset.UTC));
        responses.add(StubResponse.plain(429, "{\"error\":\"rate limited\"}", retryAt));
        responses.add(StubResponse.json(200, completion(validJson())));
        AtomicLong requestedWaitMs = new AtomicLong(-1);
        AtomicInteger requestsWhenWaitStarted = new AtomicInteger(-1);
        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget,
                new NarrativeFactualityValidator(), Clock.fixed(now, ZoneOffset.UTC), delay -> {
                    requestedWaitMs.set(delay);
                    requestsWhenWaitStarted.set(requestCount.get());
                });

        assertThat(client.generateExplanation(article())).isPresent();

        assertThat(requestedWaitMs.get()).isEqualTo(2_000);
        assertThat(requestsWhenWaitStarted.get()).isEqualTo(1);
        assertThat(requestCount.get()).isEqualTo(2);
        assertThat(usageBudget.reservedToday()).isEqualTo(2);
    }

    @Test
    void defersRetryWhenProviderRetryAfterExceedsInCallWindow() {
        properties.getAi().setMaxAttempts(2);
        responses.add(StubResponse.plain(429, "{\"error\":\"rate limited\"}", "60"));
        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(usageBudget.reservedToday()).isEqualTo(1);
        assertThat(client.isCoolingDown()).isTrue();
        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void doesNotSendConcurrentProviderRequestsWhenOneIsAlreadyActive() throws Exception {
        properties.getAi().setMaxAttempts(1);
        requestStarted = new CountDownLatch(1);
        responses.add(StubResponse.delayed(200, completion(validJson()), 700));
        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        CompletableFuture<Optional<ExplanationDraft>> first = CompletableFuture.supplyAsync(
                () -> client.generateExplanation(article()));
        assertThat(requestStarted.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(first.get(2, TimeUnit.SECONDS)).isPresent();
        assertThat(usageBudget.reservedToday()).isEqualTo(1);
    }

    @Test
    void serverErrorRetryExhaustionFallsBack() {
        responses.add(StubResponse.plain(500, "{\"error\":\"boom\"}", null));
        responses.add(StubResponse.plain(503, "{\"error\":\"down\"}", null));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void repeatedServerErrorsOpenCooldownAndSkipFurtherCalls() {
        properties.getAi().setMaxAttempts(1);
        properties.getAi().setFailureThreshold(1);
        responses.add(StubResponse.plain(503, "{\"error\":\"down\"}", null));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(client.isCoolingDown()).isTrue();
        assertThat(client.isAvailable()).isFalse();

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void dailyBudgetStopsRetriesAndFurtherCalls() {
        properties.getAi().setMaxCallsPerDay(1);
        responses.add(StubResponse.plain(500, "{\"error\":\"boom\"}", null));
        responses.add(StubResponse.json(200, completion(validJson())));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
        assertThat(client.remainingDailyCalls()).isZero();
        assertThat(client.hasBudget()).isFalse();
        assertThat(usageBudget.reservedToday()).isEqualTo(1);
        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void timeoutDoesNotThrowAndReturnsEmpty() {
        properties.getAi().setTimeoutMs(300);
        properties.getAi().setMaxAttempts(1);
        properties.getAi().setFailureThreshold(5);
        responses.add(StubResponse.delayed(200, completion(validJson()), 1500));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(client.isCoolingDown()).isFalse();
    }

    private String validJson() {
        return groundedJson("The company says the new model ships today.", "It supports offline use.",
                "The update is available for Android users.");
    }

    private String withoutField(String json, String field) {
        try {
            var mapper = new ObjectMapper();
            var object = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(json);
            object.remove(field);
            return mapper.writeValueAsString(object);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String groundedJson(String what, String whyImportant, String how) {
        try {
            return new ObjectMapper().writeValueAsString(java.util.Map.ofEntries(
                    java.util.Map.entry("whatHappened", what),
                    java.util.Map.entry("technology", "Not available in the provided text."),
                    java.util.Map.entry("importance", whyImportant),
                    java.util.Map.entry("howItWorks", how),
                    java.util.Map.entry("whereUsed", "Not available in the provided text."),
                    java.util.Map.entry("fresherLearning", "Not available in the provided text."),
                    java.util.Map.entry("practicalExample", "Not available in the provided text."),
                    java.util.Map.entry("relatedTechnologies", "Not available in the provided text."),
                    java.util.Map.entry("sourceEvidence", what),
                    java.util.Map.entry("whoBenefits", "Not available in the provided text."),
                    java.util.Map.entry("useCases", "Not available in the provided text."),
                    java.util.Map.entry("technicalExplanation", how),
                    java.util.Map.entry("whyItHappened", "The article does not say why this happened."),
                    java.util.Map.entry("usefulToReader", "The article does not describe a specific personal benefit."),
                    java.util.Map.entry("recommendedAction", "Not available in the provided text."),
                    java.util.Map.entry("claims", List.of(java.util.Map.of("section", "whatHappened", "kind", "SOURCE_FACT",
                            "statement", what, "evidence", what)))));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String withUnsupportedClaim(String json) {
        try {
            var mapper = new ObjectMapper();
            var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(json);
            ((com.fasterxml.jackson.databind.node.ArrayNode) root.get("claims")).add(mapper.createObjectNode()
                    .put("section", "importance").put("kind", "SOURCE_FACT")
                    .put("statement", "It costs $10 per month.").put("evidence", "The update is available for Android users."));
            return mapper.writeValueAsString(root);
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }

    private String researchJson() {
        return """
                {"whatHappened":"Abstract: Agents hand work to a successor as a short written summary.",
                 "technology":"Not available in the provided text.",
                 "importance":"All six models learn less in the chain.",
                 "howItWorks":"We study this in 56 interactive worlds with hidden rules.",
                 "whereUsed":"Not available in the provided text.",
                 "fresherLearning":"Not available in the provided text.",
                 "practicalExample":"Not available in the provided text.",
                 "relatedTechnologies":"Not available in the provided text.",
                 "sourceEvidence":"Abstract: Agents hand work to a successor as a short written summary.",
                 "whoBenefits":"Not available in the provided text.",
                 "useCases":"Not available in the provided text.",
                 "technicalExplanation":"All six models learn less in the chain.",
                 "whyItHappened":"The article does not say why this happened.",
                 "usefulToReader":"The article does not describe a specific personal benefit.",
                 "recommendedAction":"Not available in the provided text.",
                 "claims":[{"section":"howItWorks","kind":"SOURCE_FACT","statement":"We study this in 56 interactive worlds with hidden rules.","evidence":"We study this in 56 interactive worlds with hidden rules."}]}
                """;
    }

    private Article researchArticle() {
        Category category = new Category("AI Research", "ai-research", null);
        Article article = new Article();
        article.setId(131L);
        article.setTitle("Agents Cannot Hand Over What They Have Not Yet Understood");
        article.setUrl("https://example.com/research");
        article.setDescription("Abstract: Agents hand work to a successor as a short written summary. "
                + "We study this in 56 interactive worlds with hidden rules. All six models learn less in the chain.");
        article.setSource("cs.AI updates on arXiv.org");
        article.setCategory(category);
        return article;
    }

    private String completion(String contentJson) {
        return completion(contentJson, "stop", null);
    }

    private String completion(String contentJson, String finishReason, String refusal) {
        return completion(contentJson, finishReason, refusal, null);
    }

    private String completion(String contentJson, String finishReason, String refusal, String status) {
        try {
            java.util.Map<String, Object> message = new java.util.LinkedHashMap<>();
            message.put("content", contentJson);
            if (refusal != null) message.put("refusal", refusal);
            if (status != null) message.put("status", status);
            return new ObjectMapper().writeValueAsString(java.util.Map.of(
                    "choices", List.of(java.util.Map.of("message", message, "finish_reason", finishReason)),
                    "usage", java.util.Map.of("prompt_tokens", 120, "completion_tokens", 80)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
        if (requestStarted != null) requestStarted.countDown();
        try (InputStream body = exchange.getRequestBody()) {
            requestBodies.add(new String(body.readAllBytes(), StandardCharsets.UTF_8));
        }
        StubResponse stub = responses.isEmpty()
                ? StubResponse.plain(500, "{\"error\":\"no stub\"}", null)
                : responses.remove(0);
        if (stub.delayMs() > 0) {
            try {
                Thread.sleep(stub.delayMs());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
        if (stub.retryAfter() != null) {
            exchange.getResponseHeaders().add("Retry-After", stub.retryAfter());
        }
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        byte[] bytes = stub.body().getBytes(StandardCharsets.UTF_8);
        try {
            exchange.sendResponseHeaders(stub.status(), bytes.length);
            exchange.getResponseBody().write(bytes);
        } catch (IOException ignored) {
            // The client may have already timed out and closed the connection.
        } finally {
            exchange.close();
        }
    }

    @Test
    void researchAbstractGetsAResearchDraftWithProvenance() {
        responses.add(StubResponse.json(200, completion(researchJson())));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget,
                new NarrativeFactualityValidator());
        Optional<ExplanationDraft> draft = client.generateExplanation(researchArticle());

        assertThat(draft).isPresent();
        assertThat(draft.get().howItWorks()).contains("56 interactive worlds");
        assertThat(draft.get().claims()).isNotEmpty();
        assertThat(draft.get().claims().get(0).section()).isEqualTo("howItWorks");
        assertThat(draft.get().claims().get(0).kind())
                .isEqualTo(ExplanationDraft.Claim.Kind.SOURCE_FACT);
        assertThat(requestBodies).hasSize(1);
        assertThat(requestBodies.get(0)).contains("We study this in 56 interactive worlds")
                .contains("Source: EXCERPT / PARTIAL");
    }

    @Test
    void requestPromptRequiresBeginnerFriendlyStructuredExplanation() {
        responses.add(StubResponse.json(200, completion(validJson())));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget,
                new NarrativeFactualityValidator());
        client.generateExplanation(article());

        String request = requestBodies.get(requestBodies.size() - 1);
        assertThat(request).contains("simple English", "five narrative fields", "whyItHappened",
                "usefulToReader", "recommendedAction", "whatHappened", "explicitly stated cause",
                "never transfer a capability between subjects", "publisher explicitly instructs readers");
    }

    @Test
    void requestSourceIsBoundedAndExplicitlyMarkedTruncated() throws Exception {
        properties.getAi().setMaxAttempts(1);
        properties.getAi().setMaxSourceTokens(18);
        responses.add(StubResponse.plain(503, "{}", null));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget,
                new NarrativeFactualityValidator());
        client.generateExplanation(article());

        var request = new ObjectMapper().readTree(requestBodies.get(0));
        String prompt = request.path("messages").path(1).path("content").asText();
        assertThat(prompt).contains("Source: EXCERPT / TRUNCATED",
                "Source is truncated at a sentence boundary");
        assertThat(prompt).contains("The company says the new model ships today.");
        assertThat(prompt).contains("It supports offline use.")
                .doesNotContain("The update is available for Android users.");
    }

    @Test
    void researchAndProductArticlesUseTheSameFiveSectionFormat() {
        responses.add(StubResponse.json(200, completion(validJson())));
        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget,
                new NarrativeFactualityValidator());
        client.generateExplanation(article());
        String productPrompt = requestBodies.get(0);

        responses.add(StubResponse.json(200, completion(researchJson())));
        client.generateExplanation(researchArticle());
        String researchPrompt = requestBodies.get(1);

        assertThat(productPrompt).contains("five narrative fields", "whyItHappened", "usefulToReader", "recommendedAction");
        assertThat(researchPrompt).contains("five narrative fields", "whyItHappened", "usefulToReader", "recommendedAction");
        assertThat(productPrompt).doesNotContain("Published:", "Original URL:", "Category:");
        assertThat(researchPrompt).doesNotContain("Published:", "Original URL:", "Category:");
    }

    @Test
    void allArticleCategoriesUseTheSameFiveSectionSchema() {
        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget,
                new NarrativeFactualityValidator());
        List<ArticleFixture> fixtures = List.of(
                new ArticleFixture("AI Tools", "Luma adds offline mode", "Luma adds an offline mode to its writing app. The update works on Android. The company says users can draft without a network connection."),
                new ArticleFixture("AI Research", "Battery study tests solvent", "The team tested a battery-recycling solvent in laboratory samples. It recovered 92% of the lithium in those samples. The authors say larger trials are still needed."),
                new ArticleFixture("Business", "Harbor Bakery opens shop", "Harbor Bakery opened a second shop on Elm Street. The owners said weekday lunch demand led to the expansion. The shop sells bread and prepared lunches."),
                new ArticleFixture("Policy", "City launches flood alerts", "The city launched a flood-warning text service. It sends alerts when river gauges pass the published warning level. Residents can register a mobile number on the city website."),
                new ArticleFixture("Education", "North School starts coding club", "North School started a weekly coding club. Students meet each Friday to make small games. Volunteer mentors help students use the school's computers."),
                new ArticleFixture("Health", "Clinic extends evening hours", "The clinic extended its evening hours to 8 p.m. The manager said more patients requested appointments after work. Patients can book through the clinic's website."));
        for (ArticleFixture fixture : fixtures) {
            String[] sentences = fixture.description().split("(?<=[.!?])\\s+");
            responses.add(StubResponse.json(200, completion(groundedJson(sentences[0], sentences[1], sentences[2]))));
            Article candidate = article();
            candidate.setTitle(fixture.title());
            candidate.setDescription(fixture.description());
            candidate.setCategory(new Category(fixture.category(), fixture.category().toLowerCase().replace(' ', '-'), null));

            assertThat(client.generateExplanation(candidate)).as(fixture.category()).isPresent();
            String request = requestBodies.get(requestBodies.size() - 1);
            assertThat(request).contains("five narrative fields", "whyItHappened", "importance",
                    "usefulToReader", "recommendedAction");
        }
    }

    private record ArticleFixture(String category, String title, String description) {}

    @Test
    void researchDraftThatIsMostlyUnavailableIsRejected() {
        properties.getAi().setMaxAttempts(1);
        String unavailable = "Not available in the provided text.";
        String json = """
                {"whatHappened":"%s","technology":"%s","importance":"%s","howItWorks":"%s",
                 "whereUsed":"%s","fresherLearning":"%s","practicalExample":"%s",
                 "relatedTechnologies":"%s","sourceEvidence":"We study this in 56 interactive worlds with hidden rules.",
                 "whoBenefits":"Researchers studying agents.","useCases":"%s","technicalExplanation":"%s",
                 "claims":[{"section":"whoBenefits","kind":"SOURCE_FACT","statement":"The study has an audience.","evidence":"We study this in 56 interactive worlds with hidden rules."}]}
                """.formatted(unavailable, unavailable, unavailable, unavailable, unavailable,
                unavailable, unavailable, unavailable, unavailable, unavailable);
        responses.add(StubResponse.json(200, completion(json)));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget,
                new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(researchArticle())).isEmpty();
    }

    @Test
    void unavailableLedgerFailsClosedWithoutCallingTheProvider() {
        usageBudget.failReads = true;
        usageBudget.failReservations = true;
        responses.add(StubResponse.json(200, completion(validJson())));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.hasBudget()).isFalse();
        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isZero();
    }

    private Article article() {
        Category category = new Category("Generative AI", "generative-ai", null);
        Article article = new Article();
        article.setId(1L);
        article.setTitle("New model ships");
        article.setUrl("https://example.com/a");
        article.setDescription("The company says the new model ships today. It supports offline use. The update is available for Android users.");
        article.setSource("Example Feed");
        article.setCategory(category);
        return article;
    }

    private static final class InMemoryAiUsageBudget implements AiUsageBudget {
        private final java.util.Map<LocalDate, Integer> attempts = new java.util.HashMap<>();
        private boolean failReads;
        private boolean failReservations;

        @Override
        public boolean tryReserve(LocalDate usageDate, int maxCalls) {
            if (failReservations) {
                return false;
            }
            int used = attempts.getOrDefault(usageDate, 0);
            if (maxCalls > 0 && used >= maxCalls) {
                return false;
            }
            attempts.put(usageDate, used + 1);
            return true;
        }

        @Override
        public int attempts(LocalDate usageDate) {
            if (failReads) {
                throw new IllegalStateException("ledger down");
            }
            return attempts.getOrDefault(usageDate, 0);
        }

        @Override
        public void recordSuccess(LocalDate usageDate, int inputTokens, int outputTokens) {
        }

        @Override
        public void recordFailure(LocalDate usageDate) {
        }

        int reservedToday() {
            return attempts.getOrDefault(LocalDate.now(ZoneId.of("Asia/Kolkata")), 0);
        }
    }

    private record StubResponse(int status, String body, String retryAfter, long delayMs) {
        static StubResponse json(int status, String body) {
            return new StubResponse(status, body, null, 0);
        }

        static StubResponse plain(int status, String body, String retryAfter) {
            return new StubResponse(status, body, retryAfter, 0);
        }

        static StubResponse delayed(int status, String body, long delayMs) {
            return new StubResponse(status, body, null, delayMs);
        }
    }
}
