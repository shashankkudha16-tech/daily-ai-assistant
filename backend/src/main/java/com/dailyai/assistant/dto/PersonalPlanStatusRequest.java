package com.dailyai.assistant.dto;

import jakarta.validation.constraints.NotNull;
import com.dailyai.assistant.entity.PersonalLearningPlanItem;

public record PersonalPlanStatusRequest(@NotNull PersonalLearningPlanItem.Status status) { }
