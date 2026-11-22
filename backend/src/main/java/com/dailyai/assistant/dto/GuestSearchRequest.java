package com.dailyai.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GuestSearchRequest(@NotBlank @Size(max = 120) String query) { }
