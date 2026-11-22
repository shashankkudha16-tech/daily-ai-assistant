package com.dailyai.assistant.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record GuestInteractionRequest(@NotNull @Positive Long articleId, @NotNull Type type) {
    public enum Type { OPEN, QUALIFIED_VIEW }
}
