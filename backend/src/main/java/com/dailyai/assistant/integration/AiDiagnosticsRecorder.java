package com.dailyai.assistant.integration;

import com.dailyai.assistant.entity.AiRequestDiagnostic;
import com.dailyai.assistant.repository.AiRequestDiagnosticRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.regex.Pattern;

/** Records only bounded enums, provider codes, status, and token counts. */
@Component
public class AiDiagnosticsRecorder {
    private static final Logger log = LoggerFactory.getLogger(AiDiagnosticsRecorder.class);
    private static final Pattern SAFE_CODE = Pattern.compile("[A-Za-z0-9_.-]{1,80}");
    private final AiRequestDiagnosticRepository repository;

    public AiDiagnosticsRecorder(AiRequestDiagnosticRepository repository) { this.repository = repository; }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Event event) {
        AiRequestDiagnostic row = new AiRequestDiagnostic(event.usageDate(), clean(event.operation(), 32),
                clean(event.provider(), 32), clean(event.model(), 100));
        row.setHttpStatus(event.httpStatus());
        row.setProviderErrorCode(safeCode(event.providerErrorCode(), 100));
        row.setFinishReason(safeCode(event.finishReason(), 40));
        row.setPromptTokens(event.promptTokens());
        row.setCompletionTokens(event.completionTokens());
        row.setTotalTokens(event.totalTokens());
        row.setJsonParsed(event.jsonParsed());
        row.setSchemaValid(event.schemaValid());
        row.setOutcome(clean(event.outcome(), 40));
        row.setFieldValidationResult(safeCode(event.fieldValidationResult(), 80));
        row.setFactualityRejectionReason(safeCode(event.factualityRejectionReason(), 40));
        repository.save(row);
        log.info("AI diagnostic operation={} provider={} model={} status={} errorCode={} finish={} promptTokens={} completionTokens={} totalTokens={} jsonParsed={} schemaValid={} outcome={} fieldResult={} factualityReason={}",
                row.getOperation(), row.getProvider(), row.getModel(), row.getHttpStatus(), row.getProviderErrorCode(),
                row.getFinishReason(), row.getPromptTokens(), row.getCompletionTokens(), row.getTotalTokens(),
                row.isJsonParsed(), row.isSchemaValid(), row.getOutcome(), row.getFieldValidationResult(),
                row.getFactualityRejectionReason());
    }

    private String safeCode(String value, int maxLength) {
        return value != null && value.length() <= maxLength && SAFE_CODE.matcher(value).matches() ? value : null;
    }

    private String clean(String value, int max) {
        if (value == null) return "unknown";
        String safe = value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return safe.substring(0, Math.min(max, safe.length()));
    }

    public record Event(LocalDate usageDate, String operation, String provider, String model, Integer httpStatus,
                        String providerErrorCode, String finishReason, int promptTokens, int completionTokens,
                        int totalTokens, boolean jsonParsed, boolean schemaValid, String outcome,
                        String fieldValidationResult, String factualityRejectionReason) { }
}
