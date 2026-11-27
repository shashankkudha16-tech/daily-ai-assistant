package com.dailyai.assistant.service;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.integration.AiUsageBudget;
import com.dailyai.assistant.integration.AiDiagnosticsRecorder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import org.springframework.http.HttpStatus;
import java.io.IOException;

class MentorAiClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AppProperties properties = properties();
    private final AiUsageBudget budget = mock(AiUsageBudget.class);
    private final AiDiagnosticsRecorder diagnostics = mock(AiDiagnosticsRecorder.class);

    @Test
    void sendsStrictSchemaAndAcceptsOnlyRecommendationsWithKnownEvidence() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String output = "{\"recommendations\":[" + recommendation("PRACTICE", "PRACTICE_RECORDED_TOPIC",
                "Java collections practice", "", "MEDIUM", 25, "skill-1") + "]}";
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(jsonPath("$.model").value("openai/gpt-oss-20b"))
                .andExpect(jsonPath("$.include_reasoning").value(false))
                .andExpect(jsonPath("$.max_completion_tokens").value(4096))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andRespond(withSuccess(providerResponse(output), MediaType.APPLICATION_JSON));
        when(budget.tryReserve(any(LocalDate.class), eq(100))).thenReturn(true);

        var result = new MentorAiClient(properties, mapper, budget, builder.build(), diagnostics)
                .recommend(List.of(new MentorAiClient.Evidence("skill-1", "SKILL", "Java collections practice", "")), LocalDate.now());

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().recommendations()).hasSize(1);
        assertThat(result.orElseThrow().recommendations().get(0).relatedSkill()).isEmpty();
        verify(budget).recordSuccess(any(LocalDate.class), eq(120), eq(45));
        ArgumentCaptor<AiDiagnosticsRecorder.Event> event = ArgumentCaptor.forClass(AiDiagnosticsRecorder.Event.class);
        verify(diagnostics).record(event.capture());
        assertThat(event.getValue().httpStatus()).isEqualTo(200);
        assertThat(event.getValue().finishReason()).isEqualTo("stop");
        assertThat(event.getValue().totalTokens()).isEqualTo(165);
        assertThat(event.getValue().jsonParsed()).isTrue();
        assertThat(event.getValue().schemaValid()).isTrue();
        assertThat(event.getValue().outcome()).isEqualTo("ACCEPTED");
        server.verify();
    }

    @Test
    void rejectsRecommendationWithUnknownEvidenceAndRecordsFailure() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String output = "{\"recommendations\":[" + recommendation("PRACTICE", "PRACTICE_RECORDED_TOPIC",
                "Practiced Java collections", "", "LOW", 20, "invented-id") + "]}";
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(withSuccess(providerResponse(output), MediaType.APPLICATION_JSON));
        when(budget.tryReserve(any(LocalDate.class), anyInt())).thenReturn(true);

        var result = new MentorAiClient(properties, mapper, budget, builder.build(), diagnostics)
                .recommend(List.of(new MentorAiClient.Evidence("journal-1", "JOURNAL", "Practiced Java", "2026-11-25")), LocalDate.now());

        assertThat(result).isEmpty();
        verify(budget).recordFailure(any(LocalDate.class), anyInt(), anyInt());
        verify(budget, never()).recordSuccess(any(LocalDate.class), anyInt(), anyInt());
        server.verify();
    }

    @Test
    void handlesRateLimitOnceWithoutRetrying() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));
        when(budget.tryReserve(any(LocalDate.class), anyInt())).thenReturn(true);

        var result = new MentorAiClient(properties, mapper, budget, builder.build(), diagnostics)
                .recommend(List.of(new MentorAiClient.Evidence("one", "JOURNAL", "Text", "2026-11-25")), LocalDate.now());

        assertThat(result).isEmpty();
        verify(budget).recordFailure(any(LocalDate.class), anyInt(), anyInt());
        server.verify();
    }

    @Test
    void skipsProviderWhenTheDailyBudgetIsExhausted() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        when(budget.tryReserve(any(LocalDate.class), anyInt())).thenReturn(false);
        var result = new MentorAiClient(properties, mapper, budget, builder.build(), diagnostics)
                .recommend(List.of(new MentorAiClient.Evidence("one", "JOURNAL", "Text", "2026-11-25")), LocalDate.now());
        assertThat(result).isEmpty();
        verifyNoInteractionsAfterReserve(budget);
        server.verify();
    }

    @Test
    void failsClosedFor400429And500WithoutRetrying() {
        for (HttpStatus status : List.of(HttpStatus.BAD_REQUEST, HttpStatus.TOO_MANY_REQUESTS, HttpStatus.INTERNAL_SERVER_ERROR)) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://provider.test/v1/chat/completions")).andRespond(withStatus(status));
            when(budget.tryReserve(any(LocalDate.class), anyInt())).thenReturn(true);
            assertThat(new MentorAiClient(properties, mapper, budget, builder.build(), diagnostics)
                    .recommend(List.of(new MentorAiClient.Evidence("one", "JOURNAL", "Practiced Java", "")), LocalDate.now()))
                    .isEmpty();
            verify(budget).recordFailure(any(LocalDate.class), anyInt(), anyInt());
            server.verify();
            clearInvocations(budget);
        }
    }

    @Test
    void timeoutReturnsNoRecommendationAndDoesNotRetry() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(withException(new IOException("simulated timeout")));
        when(budget.tryReserve(any(LocalDate.class), anyInt())).thenReturn(true);
        assertThat(new MentorAiClient(properties, mapper, budget, builder.build(), diagnostics)
                .recommend(List.of(new MentorAiClient.Evidence("one", "JOURNAL", "Practiced Java", "")), LocalDate.now()))
                .isEmpty();
        verify(budget).recordFailure(any(LocalDate.class), anyInt(), anyInt());
        server.verify();
    }

    @Test
    void malformedJsonAndInvalidSchemaAreRejected() throws Exception {
        for (String output : List.of("{", "{\"unexpected\":[]}")) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://provider.test/v1/chat/completions"))
                    .andRespond(withSuccess(providerResponse(output), MediaType.APPLICATION_JSON));
            when(budget.tryReserve(any(LocalDate.class), anyInt())).thenReturn(true);
            assertThat(new MentorAiClient(properties, mapper, budget, builder.build(), diagnostics)
                    .recommend(List.of(new MentorAiClient.Evidence("one", "JOURNAL", "Practiced Java", "")), LocalDate.now()))
                    .isEmpty();
            verify(budget).recordFailure(any(LocalDate.class), anyInt(), anyInt());
            server.verify();
            clearInvocations(budget);
        }
    }

    @Test
    void reportsContradictionAndPersistsTokenUsageForRejectedContent() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String output = "{\"recommendations\":[" + recommendation("PRACTICE", "PRACTICE_RECORDED_TOPIC",
                "The user practiced HashMap internals", "", "MEDIUM", 20, "journal-1") + "]}";
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(withSuccess(providerResponse(output), MediaType.APPLICATION_JSON));
        when(budget.tryReserve(any(LocalDate.class), anyInt())).thenReturn(true);

        assertThat(new MentorAiClient(properties, mapper, budget, builder.build(), diagnostics)
                .recommend(List.of(new MentorAiClient.Evidence("journal-1", "CHALLENGE",
                        "The user did not practice HashMap internals", "2026-11-24")), LocalDate.now())).isEmpty();

        verify(budget).recordFailure(any(LocalDate.class), eq(120), eq(45));
        ArgumentCaptor<AiDiagnosticsRecorder.Event> event = ArgumentCaptor.forClass(AiDiagnosticsRecorder.Event.class);
        verify(diagnostics).record(event.capture());
        assertThat(event.getValue().outcome()).isEqualTo("CONTRADICTORY_CLAIM");
        assertThat(event.getValue().factualityRejectionReason()).isEqualTo("CONTRADICTORY_CLAIM");
        assertThat(event.getValue().fieldValidationResult()).isEqualTo("RECOMMENDATIONS_0_RATIONALE_CONTRADICTS_EVIDENCE");
        assertThat(event.getValue().jsonParsed()).isTrue();
        assertThat(event.getValue().schemaValid()).isTrue();
        server.verify();
    }

    @Test
    void capturesProviderErrorCodeWithoutPersistingProviderMessage() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String error = "{\"error\":{\"code\":\"json_validate_failed\",\"message\":\"sensitive prompt text\"},\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":3,\"total_tokens\":15}}";
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST).body(error).contentType(MediaType.APPLICATION_JSON));
        when(budget.tryReserve(any(LocalDate.class), anyInt())).thenReturn(true);

        assertThat(new MentorAiClient(properties, mapper, budget, builder.build(), diagnostics)
                .recommend(List.of(new MentorAiClient.Evidence("e1", "JOURNAL", "Java collections", "")), LocalDate.now())).isEmpty();

        verify(budget).recordFailure(any(LocalDate.class), eq(12), eq(3));
        ArgumentCaptor<AiDiagnosticsRecorder.Event> event = ArgumentCaptor.forClass(AiDiagnosticsRecorder.Event.class);
        verify(diagnostics).record(event.capture());
        assertThat(event.getValue().httpStatus()).isEqualTo(400);
        assertThat(event.getValue().providerErrorCode()).isEqualTo("json_validate_failed");
        assertThat(event.getValue().outcome()).isEqualTo("PROVIDER_FAILURE");
        server.verify();
    }

    @Test
    void diagnosticsPersistenceFailureDoesNotTurnAcceptedResponseIntoBasicFallback() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String output = "{\"recommendations\":[" + recommendation("PRACTICE", "PRACTICE_RECORDED_TOPIC",
                "Java collections practice", "", "MEDIUM", 25, "skill-1") + "]}";
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(withSuccess(providerResponse(output), MediaType.APPLICATION_JSON));
        when(budget.tryReserve(any(LocalDate.class), anyInt())).thenReturn(true);
        doThrow(new IllegalStateException("must not leak this diagnostic failure"))
                .when(diagnostics).record(any(AiDiagnosticsRecorder.Event.class));

        var result = new MentorAiClient(properties, mapper, budget, builder.build(), diagnostics)
                .recommend(List.of(new MentorAiClient.Evidence("skill-1", "SKILL", "Java collections practice", "")), LocalDate.now());

        assertThat(result).isPresent();
        verify(budget).recordSuccess(any(LocalDate.class), eq(120), eq(45));
        verify(budget, never()).recordFailure(any(LocalDate.class), anyInt(), anyInt());
        server.verify();
    }

    @Test
    void rejectsUnsupportedAchievementClaimsInsteadOfReturningAiSuccess() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String output = "{\"recommendations\":[{\"title\":\"You have mastered Java\",\"action\":\"You are ready for senior jobs\",\"rationale\":\"Java skill\",\"relatedSkill\":\"Java\",\"priority\":\"HIGH\",\"estimatedMinutes\":20,\"evidenceIds\":[\"skill-1\"]}]}";
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(withSuccess(providerResponse(output), MediaType.APPLICATION_JSON));
        when(budget.tryReserve(any(LocalDate.class), anyInt())).thenReturn(true);
        assertThat(new MentorAiClient(properties, mapper, budget, builder.build(), diagnostics)
                .recommend(List.of(new MentorAiClient.Evidence("skill-1", "SKILL", "Java", "")), LocalDate.now()))
                .isEmpty();
        verify(budget).recordFailure(any(LocalDate.class), anyInt(), anyInt());
        verify(budget, never()).recordSuccess(any(LocalDate.class), anyInt(), anyInt());
        server.verify();
    }

    @Test
    void rejectsUnrelatedBusinessProjectAndUnsupportedAchievementButKeepsValidSibling() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String unrelated = "{\"actionType\":\"PRACTICE\",\"expectedOutcome\":\"PRACTICE_RECORDED_TOPIC\","
                + "\"rationale\":\"HashMap internals were difficult\",\"relatedSkill\":\"\",\"priority\":\"HIGH\","
                + "\"estimatedMinutes\":30,\"evidenceId\":\"difficulty-1\",\"title\":\"Practice HashMap\","
                + "\"action\":\"Build a stock trading business that guarantees an offer\"}";
        String valid = recommendation("PRACTICE", "PRACTICE_RECORDED_TOPIC", "HashMap internals were difficult",
                "", "MEDIUM", 20, "difficulty-1");
        String output = "{\"recommendations\":[" + unrelated + "," + valid + "]}";
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(withSuccess(providerResponse(output), MediaType.APPLICATION_JSON));
        when(budget.tryReserve(any(LocalDate.class), anyInt())).thenReturn(true);

        var result = new MentorAiClient(properties, mapper, budget, builder.build(), diagnostics).recommend(List.of(
                new MentorAiClient.Evidence("difficulty-1", "CHALLENGE", "HashMap internals were difficult", "2026-11-24")), LocalDate.now());

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().recommendations()).hasSize(1);
        assertThat(result.orElseThrow().recommendations().get(0).title()).startsWith("Practice:");
        assertThat(result.orElseThrow().recommendations().get(0).action()).doesNotContain("stock trading", "guarantees");
        ArgumentCaptor<AiDiagnosticsRecorder.Event> event = ArgumentCaptor.forClass(AiDiagnosticsRecorder.Event.class);
        verify(diagnostics).record(event.capture());
        assertThat(event.getValue().outcome()).isEqualTo("PARTIALLY_ACCEPTED");
        server.verify();
    }

    @Test
    void rejectsActionTypeThatCannotBeDerivedFromEvidence() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String invalid = recommendation("BUILD_BUSINESS_PROJECT", "PRACTICE_RECORDED_TOPIC",
                "HashMap internals were difficult", "", "MEDIUM", 20, "d1");
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(withSuccess(providerResponse("{\"recommendations\":[" + invalid + "]}"), MediaType.APPLICATION_JSON));
        when(budget.tryReserve(any(LocalDate.class), anyInt())).thenReturn(true);

        assertThat(new MentorAiClient(properties, mapper, budget, builder.build(), diagnostics)
                .recommend(List.of(new MentorAiClient.Evidence("d1", "CHALLENGE", "HashMap internals were difficult", "2026-11-24")), LocalDate.now()))
                .isEmpty();
        verify(budget).recordFailure(any(LocalDate.class), eq(120), eq(45));
        server.verify();
    }

    @Test
    void missingApiConfigurationSkipsReservationAndProvider() {
        properties.getAi().setApiKey("");
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        assertThat(new MentorAiClient(properties, mapper, budget, builder.build(), diagnostics)
                .recommend(List.of(new MentorAiClient.Evidence("one", "JOURNAL", "Practiced Java", "")), LocalDate.now()))
                .isEmpty();
        verifyNoInteractions(budget);
        server.verify();
    }

    private static void verifyNoInteractionsAfterReserve(AiUsageBudget budget) {
        verify(budget, never()).recordSuccess(any(LocalDate.class), anyInt(), anyInt());
        verify(budget, never()).recordFailure(any(LocalDate.class), anyInt(), anyInt());
    }

    private String providerResponse(String content) throws Exception {
        return mapper.writeValueAsString(mapper.readTree("""
                {"choices":[{"finish_reason":"stop","message":{"content":%s}}],"usage":{"prompt_tokens":120,"completion_tokens":45}}
                """.formatted(mapper.writeValueAsString(content))));
    }

    private String recommendation(String actionType, String outcome, String rationale, String relatedSkill,
                                  String priority, int minutes, String evidenceId) {
        return "{\"actionType\":\"" + actionType + "\",\"expectedOutcome\":\"" + outcome
                + "\",\"rationale\":\"" + rationale + "\",\"relatedSkill\":\"" + relatedSkill
                + "\",\"priority\":\"" + priority + "\",\"estimatedMinutes\":" + minutes
                + ",\"evidenceId\":\"" + evidenceId + "\"}";
    }

    private AppProperties properties() {
        AppProperties value = new AppProperties();
        value.getAi().setEnabled(true);
        value.getAi().setProvider("groq");
        value.getAi().setModel("openai/gpt-oss-20b");
        value.getAi().setApiKey("test-key");
        value.getAi().setBaseUrl("https://provider.test/v1");
        value.getAi().setMaxCallsPerDay(100);
        value.getAi().setMaxCompletionTokens(4096);
        return value;
    }
}
