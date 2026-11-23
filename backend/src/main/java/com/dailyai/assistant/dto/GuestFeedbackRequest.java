package com.dailyai.assistant.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record GuestFeedbackRequest(@NotNull @Positive Long articleId, @NotNull Action action) {
    public enum Action { USEFUL, NOT_USEFUL, SHOW_ME_FEWER, EXPLORE_LATER }
}
