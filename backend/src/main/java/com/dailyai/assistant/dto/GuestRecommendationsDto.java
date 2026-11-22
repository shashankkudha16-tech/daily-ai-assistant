package com.dailyai.assistant.dto;

import java.util.List;

public record GuestRecommendationsDto(List<ArticleDto> articles, boolean personalized, String message) { }
