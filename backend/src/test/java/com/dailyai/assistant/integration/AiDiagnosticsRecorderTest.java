package com.dailyai.assistant.integration;

import com.dailyai.assistant.entity.AiRequestDiagnostic;
import com.dailyai.assistant.repository.AiRequestDiagnosticRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiDiagnosticsRecorderTest {
    @Test
    void rejectsFinishReasonLongerThanTheDatabaseColumnAndStoresOnlySafeMetadata() {
        AiRequestDiagnosticRepository repository = mock(AiRequestDiagnosticRepository.class);
        when(repository.save(any(AiRequestDiagnostic.class))).thenAnswer(call -> call.getArgument(0));
        AiDiagnosticsRecorder recorder = new AiDiagnosticsRecorder(repository);

        recorder.record(new AiDiagnosticsRecorder.Event(LocalDate.now(), "MENTOR_REVIEW", "groq", "model-x", 200,
                "provider_error", "f".repeat(41), 12, 3, 15, true, true,
                "UNSUPPORTED_CLAIM", "CLAIM_0_UNSUPPORTED", "UNSUPPORTED_CLAIM"));

        ArgumentCaptor<AiRequestDiagnostic> saved = ArgumentCaptor.forClass(AiRequestDiagnostic.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getFinishReason()).isNull();
        assertThat(saved.getValue().getProviderErrorCode()).isEqualTo("provider_error");
        assertThat(saved.getValue().getPromptTokens()).isEqualTo(12);
        assertThat(saved.getValue().getCompletionTokens()).isEqualTo(3);
        assertThat(saved.getValue().getTotalTokens()).isEqualTo(15);
    }
}
