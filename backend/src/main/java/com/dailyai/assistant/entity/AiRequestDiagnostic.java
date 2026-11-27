package com.dailyai.assistant.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

/** Safe, per-request AI telemetry. Never stores prompts, outputs, user IDs, or secrets. */
@Entity
@Table(name = "ai_request_diagnostics", indexes = {
        @Index(name = "idx_ai_diagnostic_requested_at", columnList = "requested_at"),
        @Index(name = "idx_ai_diagnostic_operation_date", columnList = "operation, usage_date")
})
public class AiRequestDiagnostic {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "usage_date", nullable = false) private LocalDate usageDate;
    @Column(nullable = false, length = 32) private String operation;
    @Column(nullable = false, length = 32) private String provider;
    @Column(nullable = false, length = 100) private String model;
    @Column(name = "requested_at", nullable = false) private Instant requestedAt = Instant.now();
    @Column(name = "http_status") private Integer httpStatus;
    @Column(name = "provider_error_code", length = 100) private String providerErrorCode;
    @Column(name = "finish_reason", length = 40) private String finishReason;
    @Column(name = "prompt_tokens", nullable = false) private int promptTokens;
    @Column(name = "completion_tokens", nullable = false) private int completionTokens;
    @Column(name = "total_tokens", nullable = false) private int totalTokens;
    @Column(name = "json_parsed", nullable = false) private boolean jsonParsed;
    @Column(name = "schema_valid", nullable = false) private boolean schemaValid;
    @Column(nullable = false, length = 40) private String outcome;
    @Column(name = "field_validation_result", length = 80) private String fieldValidationResult;
    @Column(name = "factuality_rejection_reason", length = 40) private String factualityRejectionReason;

    protected AiRequestDiagnostic() { }

    public AiRequestDiagnostic(LocalDate usageDate, String operation, String provider, String model) {
        this.usageDate = usageDate;
        this.operation = operation;
        this.provider = provider;
        this.model = model;
    }

    public Long getId() { return id; }
    public LocalDate getUsageDate() { return usageDate; }
    public String getOperation() { return operation; }
    public String getProvider() { return provider; }
    public String getModel() { return model; }
    public Instant getRequestedAt() { return requestedAt; }
    public Integer getHttpStatus() { return httpStatus; }
    public void setHttpStatus(Integer value) { httpStatus = value; }
    public String getProviderErrorCode() { return providerErrorCode; }
    public void setProviderErrorCode(String value) { providerErrorCode = value; }
    public String getFinishReason() { return finishReason; }
    public void setFinishReason(String value) { finishReason = value; }
    public int getPromptTokens() { return promptTokens; }
    public void setPromptTokens(int value) { promptTokens = Math.max(0, value); }
    public int getCompletionTokens() { return completionTokens; }
    public void setCompletionTokens(int value) { completionTokens = Math.max(0, value); }
    public int getTotalTokens() { return totalTokens; }
    public void setTotalTokens(int value) { totalTokens = Math.max(0, value); }
    public boolean isJsonParsed() { return jsonParsed; }
    public void setJsonParsed(boolean value) { jsonParsed = value; }
    public boolean isSchemaValid() { return schemaValid; }
    public void setSchemaValid(boolean value) { schemaValid = value; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String value) { outcome = value; }
    public String getFieldValidationResult() { return fieldValidationResult; }
    public void setFieldValidationResult(String value) { fieldValidationResult = value; }
    public String getFactualityRejectionReason() { return factualityRejectionReason; }
    public void setFactualityRejectionReason(String value) { factualityRejectionReason = value; }
}
