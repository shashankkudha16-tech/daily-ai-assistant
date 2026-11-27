package com.dailyai.assistant.service;

import com.dailyai.assistant.config.AppProperties;
import com.dailyai.assistant.integration.AiDiagnosticsRecorder;
import com.dailyai.assistant.integration.AiUsageBudget;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class LearningPlanAiClientTest {
    private final AppProperties properties = properties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiUsageBudget budget = mock(AiUsageBudget.class);
    private final AiDiagnosticsRecorder diagnostics = mock(AiDiagnosticsRecorder.class);

    @Test
    void acceptsEvidenceBoundTaskAndUsesStrictSchema() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String content = "{\"tasks\":[" + task("PRACTICE", "PRACTICE_EXERCISE", "PRACTICE_RECORDED_DIFFICULTY",
                "HashMap collision handling was recorded as difficult", "MEDIUM", 25, "difficulty-1") + "]}";
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.model").value("openai/gpt-oss-20b"))
                .andExpect(jsonPath("$.response_format.type").value("json_schema"))
                .andExpect(jsonPath("$.response_format.json_schema.strict").value(true))
                .andExpect(jsonPath("$.response_format.json_schema.schema.additionalProperties").value(false))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.tasks.items.additionalProperties").value(false))
                .andExpect(jsonPath("$.response_format.json_schema.schema.properties.tasks.items.required.length()").value(7))
                .andRespond(withSuccess(providerResponse(content), MediaType.APPLICATION_JSON));
        when(budget.tryReserve(any(LocalDate.class), eq(100))).thenReturn(true);

        var result = client(builder).generate(List.of(new LearningPlanAiClient.Evidence("difficulty-1", "CHALLENGE",
                "HashMap collision handling was recorded as difficult", "HashMap")), LocalDate.now());

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().tasks()).hasSize(1);
        assertThat(result.orElseThrow().tasks().get(0).section()).isEqualTo("PRACTICE");
        verify(budget).recordSuccess(any(LocalDate.class), eq(120), eq(45));
        verify(diagnostics).record(argThat(event -> event.schemaValid() && event.jsonParsed()
                && event.outcome().equals("ACCEPTED")));
        server.verify();
    }

    @Test
    void usageIsBookedOnRequestDateNotFuturePlanDate() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String content = "{\"tasks\":[]}";
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(withSuccess(providerResponse(content), MediaType.APPLICATION_JSON));
        when(budget.tryReserve(any(LocalDate.class), eq(100))).thenReturn(true);
        LocalDate requestDate = LocalDate.now(ZoneId.of(properties.getTimezone()));

        assertThat(client(builder).generate(List.of(), requestDate.plusDays(1))).isPresent();

        verify(budget).tryReserve(eq(requestDate), eq(100));
        verify(budget).recordSuccess(eq(requestDate), eq(120), eq(45));
        verify(diagnostics).record(argThat(event -> event.usageDate().equals(requestDate)));
        server.verify();
    }

    @Test
    void rejectsRationaleNotSupportedByEvidenceAndPersistsUsage() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String content = "{\"tasks\":[" + task("PRACTICE", "PRACTICE_EXERCISE", "PRACTICE_RECORDED_DIFFICULTY",
                "The learner mastered distributed systems", "LOW", 20, "d1") + "]}";
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(withSuccess(providerResponse(content), MediaType.APPLICATION_JSON));
        when(budget.tryReserve(any(LocalDate.class), anyInt())).thenReturn(true);

        assertThat(client(builder).generate(List.of(new LearningPlanAiClient.Evidence("d1", "CHALLENGE",
                "HashMap collision handling was recorded as difficult", "HashMap")), LocalDate.now())).isEmpty();
        verify(budget).recordFailure(any(LocalDate.class), eq(120), eq(45));
        verify(diagnostics).record(argThat(event -> event.outcome().equals("UNSUPPORTED_CLAIM")
                && event.factualityRejectionReason().equals("UNSUPPORTED_CLAIM")
                && event.fieldValidationResult().contains("RATIONALE_NOT_EXACT_EVIDENCE")));
        server.verify();
    }

    @Test
    void rejectsMissingFieldsAndDoesNotRetryProviderErrors() throws Exception {
        for (var response : List.of(
                withSuccess(providerResponse("{\"tasks\":[{\"section\":\"LEARN\"}]}"), MediaType.APPLICATION_JSON),
                withStatus(HttpStatus.BAD_REQUEST).body("{\"error\":{\"code\":\"bad_request\"}}").contentType(MediaType.APPLICATION_JSON))) {
            RestClient.Builder builder = RestClient.builder();
            MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
            server.expect(requestTo("https://provider.test/v1/chat/completions")).andRespond(response);
            when(budget.tryReserve(any(LocalDate.class), anyInt())).thenReturn(true);
            assertThat(client(builder).generate(List.of(new LearningPlanAiClient.Evidence("r1", "ROADMAP_TOPIC",
                    "Spring REST APIs are a career roadmap topic", "Spring")), LocalDate.now())).isEmpty();
            verify(budget).recordFailure(any(LocalDate.class), anyInt(), anyInt());
            server.verify();
            clearInvocations(budget, diagnostics);
        }
    }

    @Test
    void rejectsFreeFormBusinessProjectAndKeepsValidSiblingIndependently() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String unrelated = "{\"section\":\"PRACTICE\",\"actionType\":\"PRACTICE_EXERCISE\","
                + "\"expectedOutcome\":\"PRACTICE_RECORDED_DIFFICULTY\",\"rationale\":\"HashMap collision handling was recorded as difficult\","
                + "\"priority\":\"HIGH\",\"estimatedMinutes\":30,\"evidenceId\":\"d1\","
                + "\"title\":\"Build a stock trading business\",\"description\":\"This guarantees a job offer\"}";
        String valid = task("PRACTICE", "PRACTICE_EXERCISE", "PRACTICE_RECORDED_DIFFICULTY",
                "HashMap collision handling was recorded as difficult", "MEDIUM", 25, "d1");
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(withSuccess(providerResponse("{\"tasks\":[" + unrelated + "," + valid + "]}"), MediaType.APPLICATION_JSON));
        when(budget.tryReserve(any(LocalDate.class), anyInt())).thenReturn(true);

        var result = client(builder).generate(List.of(new LearningPlanAiClient.Evidence("d1", "CHALLENGE",
                "HashMap collision handling was recorded as difficult", "HashMap")), LocalDate.now());

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().tasks()).hasSize(1);
        assertThat(result.orElseThrow().tasks().get(0).actionType()).isEqualTo("PRACTICE_EXERCISE");
        verify(diagnostics).record(argThat(event -> event.outcome().equals("PARTIALLY_ACCEPTED")));
        server.verify();
    }

    @Test
    void actionAndOutcomeMustMatchEligibleEvidenceSection() throws Exception {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        String wrongAction = task("PRACTICE", "READ_ARTICLE", "READ_MATCHED_ARTICLE",
                "HashMap collision handling was recorded as difficult", "LOW", 20, "d1");
        server.expect(requestTo("https://provider.test/v1/chat/completions"))
                .andRespond(withSuccess(providerResponse("{\"tasks\":[" + wrongAction + "]}"), MediaType.APPLICATION_JSON));
        when(budget.tryReserve(any(LocalDate.class), anyInt())).thenReturn(true);

        assertThat(client(builder).generate(List.of(new LearningPlanAiClient.Evidence("d1", "CHALLENGE",
                "HashMap collision handling was recorded as difficult", "HashMap")), LocalDate.now())).isEmpty();
        verify(budget).recordFailure(any(LocalDate.class), eq(120), eq(45));
        server.verify();
    }

    private LearningPlanAiClient client(RestClient.Builder builder) {
        return new LearningPlanAiClient(properties, mapper, budget, diagnostics, builder.build());
    }

    private String providerResponse(String content) throws Exception {
        return mapper.writeValueAsString(mapper.readTree("""
                {"choices":[{"finish_reason":"stop","message":{"content":%s}}],"usage":{"prompt_tokens":120,"completion_tokens":45,"total_tokens":165}}
                """.formatted(mapper.writeValueAsString(content))));
    }

    private String task(String section, String actionType, String outcome, String rationale,
                        String priority, int minutes, String evidenceId) {
        return "{\"section\":\"" + section + "\",\"actionType\":\"" + actionType
                + "\",\"expectedOutcome\":\"" + outcome + "\",\"rationale\":\"" + rationale
                + "\",\"priority\":\"" + priority + "\",\"estimatedMinutes\":" + minutes
                + ",\"evidenceId\":\"" + evidenceId + "\"}";
    }

    private static AppProperties properties() {
        AppProperties result = new AppProperties();
        result.getAi().setEnabled(true);
        result.getAi().setProvider("groq");
        result.getAi().setModel("openai/gpt-oss-20b");
        result.getAi().setApiKey("test-key");
        result.getAi().setBaseUrl("https://provider.test/v1");
        result.getAi().setMaxCallsPerDay(100);
        return result;
    }
}
