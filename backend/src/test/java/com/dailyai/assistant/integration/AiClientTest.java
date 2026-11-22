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

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
    void withoutApiKeyTheProviderIsDisabledAndNeverCalled() {
        properties.getAi().setApiKey("");

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.isEnabled()).isFalse();
        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isZero();
    }

    @Test
    void generatesDraftWithSectionsClaimsAndUsageMetadata() {
        responses.add(StubResponse.json(200, completion(validJson())));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());
        Optional<ExplanationDraft> draft = client.generateExplanation(article());

        assertThat(draft).isPresent();
        assertThat(draft.get().whatHappened()).isEqualTo("A new model shipped.");
        assertThat(draft.get().whoBenefits()).isEqualTo("Developers comparing models.");
        assertThat(draft.get().useCases()).isEqualTo("Try a small summarisation feature.");
        assertThat(draft.get().technicalExplanation()).isEqualTo("A model predicts the next token.");
        assertThat(draft.get().claims()).hasSize(1);
        assertThat(draft.get().claims().get(0).evidence()).isEqualTo("A short feed description.");
        assertThat(draft.get().metadata().provider()).isEqualTo("mock");
        assertThat(draft.get().metadata().model()).isEqualTo("mock-model");
        assertThat(draft.get().metadata().inputTokens()).isEqualTo(120);
        assertThat(draft.get().metadata().outputTokens()).isEqualTo(80);
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void retriesRateLimitThenSucceeds() {
        responses.add(StubResponse.plain(429, "{\"error\":\"rate limited\"}", "0"));
        responses.add(StubResponse.json(200, completion(validJson())));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());
        Optional<ExplanationDraft> draft = client.generateExplanation(article());

        assertThat(draft).isPresent();
        assertThat(requestCount.get()).isEqualTo(2);
    }

    @Test
    void rejectsValidJsonWhenAnyRequiredSectionIsMissing() {
        properties.getAi().setMaxAttempts(1);
        responses.add(StubResponse.json(200, completion(validJson().replace(
                "\"whoBenefits\":\"Developers comparing models.\",", ""))));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(1);
    }

    @Test
    void rejectsExplanationWithoutQuotedSourceEvidence() {
        properties.getAi().setMaxAttempts(1);
        responses.add(StubResponse.json(200, completion(validJson().replace(
                "\"sourceEvidence\":\"A short feed description.\"",
                "\"sourceEvidence\":\"A fabricated launch date\""))));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
    }

    @Test
    void dropsUnsupportedClaimsInsteadOfTreatingOneQuoteAsProof() {
        String json = validJson().replace(
                "\"claims\":[{\"statement\":\"A new model shipped.\",\"evidence\":\"A short feed description.\"}]",
                "\"claims\":[{\"statement\":\"A new model shipped.\",\"evidence\":\"A short feed description.\"},"
                        + "{\"statement\":\"It costs $10 per month.\",\"evidence\":\"pricing was announced\"}]");
        responses.add(StubResponse.json(200, completion(json)));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());
        ExplanationDraft draft = client.generateExplanation(article()).orElseThrow();

        assertThat(draft.claims()).hasSize(1);
        assertThat(draft.claims().get(0).statement()).isEqualTo("A new model shipped.");
    }

    @Test
    void rejectsDraftWhenNoClaimCanBeGrounded() {
        properties.getAi().setMaxAttempts(1);
        responses.add(StubResponse.json(200, completion(validJson().replace(
                "\"evidence\":\"A short feed description.\"",
                "\"evidence\":\"an invented quote\""))));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
    }

    @Test
    void malformedJsonFallsBackAfterBoundedRetries() {
        responses.add(StubResponse.json(200, completion("I cannot answer that.")));
        responses.add(StubResponse.json(200, completion("Still not JSON.")));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget, new NarrativeFactualityValidator());

        assertThat(client.generateExplanation(article())).isEmpty();
        assertThat(requestCount.get()).isEqualTo(2);
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
        return """
                {"whatHappened":"A new model shipped.","technology":"A neural network.",
                 "importance":"It is faster.","howItWorks":"Not available in the provided text.",
                 "whereUsed":"Chat.","fresherLearning":"Learn transformers.",
                 "practicalExample":"Build a demo.","relatedTechnologies":"LLMs, RAG",
                 "sourceEvidence":"A short feed description.",
                 "whoBenefits":"Developers comparing models.",
                 "useCases":"Try a small summarisation feature.",
                 "technicalExplanation":"A model predicts the next token.",
                 "claims":[{"statement":"A new model shipped.","evidence":"A short feed description."}]}
                """;
    }

    private String researchJson() {
        return """
                {"whatHappened":"The study asks whether written notes preserve what an agent learned.",
                 "technology":"Not available in the provided text.",
                 "importance":"The reported result matters for how agent teams pass work on.",
                 "howItWorks":"The authors compare one long-lived agent with a chain of agents in 56 interactive worlds.",
                 "whereUsed":"Not available in the provided text.",
                 "fresherLearning":"Not available in the provided text.",
                 "practicalExample":"Not available in the provided text.",
                 "relatedTechnologies":"Not available in the provided text.",
                 "sourceEvidence":"We study this in 56 interactive worlds with hidden rules.",
                 "whoBenefits":"Researchers studying agent memory.",
                 "useCases":"Not available in the provided text.",
                 "technicalExplanation":"The abstract reports a measured handoff loss across the tested models.",
                 "claims":[{"section":"howItWorks","kind":"SOURCE_FACT","statement":"The study uses 56 interactive worlds with hidden rules.","evidence":"We study this in 56 interactive worlds with hidden rules."}]}
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
        try {
            return new ObjectMapper().writeValueAsString(java.util.Map.of(
                    "choices", List.of(java.util.Map.of("message", java.util.Map.of("content", contentJson))),
                    "usage", java.util.Map.of("prompt_tokens", 120, "completion_tokens", 80)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestCount.incrementAndGet();
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
        assertThat(requestBodies.get(0)).contains("explaining a research abstract")
                .contains("We study this in 56 interactive worlds")
                .contains("research abstract");
    }

    @Test
    void requestPromptRequiresBeginnerFriendlyStructuredExplanation() {
        responses.add(StubResponse.json(200, completion(validJson())));

        AiClient client = new AiClient(properties, new ObjectMapper(), usageBudget,
                new NarrativeFactualityValidator());
        client.generateExplanation(article());

        String request = requestBodies.get(requestBodies.size() - 1);
        assertThat(request).contains("everyday words", "2-3 simple sentences", "Why does it matter?",
                "Simple example", "Key takeaway", "Important terms");
    }

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
        article.setDescription("A short feed description. It is about a model that predicts the next token.");
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
