package com.dailyai.assistant.dto;

import jakarta.validation.constraints.Size;

public record ProfileCareerRequest(
        @Size(max = 2000) String careerGoal,
        @Size(max = 2000) String targetRoles
) {}
