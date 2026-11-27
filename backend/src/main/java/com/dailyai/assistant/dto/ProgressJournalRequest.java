package com.dailyai.assistant.dto;

import jakarta.validation.constraints.Size;

public record ProgressJournalRequest(
        @Size(max = 12000) String learned,
        @Size(max = 12000) String practiced,
        @Size(max = 12000) String built,
        @Size(max = 12000) String difficulties,
        @Size(max = 12000) String tomorrowGoals
) {}
