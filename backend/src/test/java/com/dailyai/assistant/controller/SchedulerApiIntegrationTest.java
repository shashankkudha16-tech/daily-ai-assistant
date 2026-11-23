package com.dailyai.assistant.controller;

import com.dailyai.assistant.dto.RefreshResultDto;
import com.dailyai.assistant.dto.SchedulerRefreshStatusResponse;
import com.dailyai.assistant.service.NewsIngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.scheduler-api-key=test-scheduler-key",
        "spring.task.scheduling.enabled=false"
})
class SchedulerApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private NewsIngestionService ingestionService;

    @Test
    void validSchedulerKeyTriggersAsynchronousRefresh() throws Exception {
        when(ingestionService.refreshAll()).thenReturn(new RefreshResultDto(
                Instant.now(), 1, 1, 0, 5, 2, 0, 0, List.of()));

        MvcResult result = mockMvc.perform(post("/api/scheduler/news/refresh")
                        .header("X-Scheduler-Key", "test-scheduler-key"))
                .andExpect(status().isAccepted())
                .andReturn();

        SchedulerRefreshStatusResponse response = objectMapper.readValue(
                result.getResponse().getContentAsString(), SchedulerRefreshStatusResponse.class);
        assertThat(response.runId()).isNotNull();
        assertThat(response.status()).isEqualTo("RUNNING");

        SchedulerRefreshStatusResponse status = awaitCompletion(response.runId());
        assertThat(status.status()).isEqualTo("SUCCEEDED");
        assertThat(status.saved()).isEqualTo(2);
    }

    @Test
    void missingOrInvalidSchedulerKeyIsRejected() throws Exception {
        mockMvc.perform(post("/api/scheduler/news/refresh"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/scheduler/news/refresh")
                        .header("X-Scheduler-Key", "wrong"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/scheduler/news/status")
                        .header("X-Scheduler-Key", "wrong"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void schedulerRefreshRemainsKeyProtectedAndCsrfExemptForMachineClient() throws Exception {
        when(ingestionService.refreshAll()).thenReturn(new RefreshResultDto(
                Instant.now(), 1, 1, 0, 1, 1, 0, 0, List.of()));
        mockMvc.perform(post("/api/scheduler/news/refresh").header("X-Scheduler-Key", "test-scheduler-key"))
                .andExpect(status().isAccepted());
    }

    @Test
    void alreadyRunningExecutionIsReturnedInsteadOfStartingAnother() throws Exception {
        when(ingestionService.refreshAll()).thenAnswer(invocation -> {
            Thread.sleep(1000);
            return new RefreshResultDto(Instant.now(), 1, 1, 0, 5, 1, 0, 0, List.of());
        });

        MvcResult first = mockMvc.perform(post("/api/scheduler/news/refresh")
                        .header("X-Scheduler-Key", "test-scheduler-key"))
                .andExpect(status().isAccepted())
                .andReturn();
        SchedulerRefreshStatusResponse firstResponse = objectMapper.readValue(
                first.getResponse().getContentAsString(), SchedulerRefreshStatusResponse.class);

        MvcResult second = mockMvc.perform(post("/api/scheduler/news/refresh")
                        .header("X-Scheduler-Key", "test-scheduler-key"))
                .andExpect(status().isAccepted())
                .andReturn();
        SchedulerRefreshStatusResponse secondResponse = objectMapper.readValue(
                second.getResponse().getContentAsString(), SchedulerRefreshStatusResponse.class);

        assertThat(secondResponse.runId()).isEqualTo(firstResponse.runId());
        assertThat(secondResponse.running()).isTrue();

        awaitCompletion(firstResponse.runId());
    }

    @Test
    void statusEndpointRequiresSchedulerKeyAndReturnsLatestRun() throws Exception {
        when(ingestionService.refreshAll()).thenReturn(new RefreshResultDto(
                Instant.now(), 1, 1, 0, 5, 1, 0, 0, List.of()));

        MvcResult trigger = mockMvc.perform(post("/api/scheduler/news/refresh")
                        .header("X-Scheduler-Key", "test-scheduler-key"))
                .andExpect(status().isAccepted())
                .andReturn();
        SchedulerRefreshStatusResponse triggered = objectMapper.readValue(
                trigger.getResponse().getContentAsString(), SchedulerRefreshStatusResponse.class);

        awaitCompletion(triggered.runId());

        MvcResult statusResult = mockMvc.perform(get("/api/scheduler/news/status")
                        .header("X-Scheduler-Key", "test-scheduler-key"))
                .andExpect(status().isOk())
                .andReturn();
        SchedulerRefreshStatusResponse status = objectMapper.readValue(
                statusResult.getResponse().getContentAsString(), SchedulerRefreshStatusResponse.class);
        assertThat(status.runId()).isEqualTo(triggered.runId());
        assertThat(status.status()).isEqualTo("SUCCEEDED");
    }

    private SchedulerRefreshStatusResponse awaitCompletion(Long runId) throws Exception {
        for (int i = 0; i < 50; i++) {
            MvcResult result = mockMvc.perform(get("/api/scheduler/news/status")
                            .header("X-Scheduler-Key", "test-scheduler-key"))
                    .andExpect(status().isOk())
                    .andReturn();
            SchedulerRefreshStatusResponse status = objectMapper.readValue(
                    result.getResponse().getContentAsString(), SchedulerRefreshStatusResponse.class);
            if (runId.equals(status.runId()) && !status.running()) {
                return status;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("RSS refresh did not complete");
    }
}
