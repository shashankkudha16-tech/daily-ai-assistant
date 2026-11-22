package com.dailyai.assistant.controller;

import com.dailyai.assistant.dto.ArticleDto;
import com.dailyai.assistant.exception.GlobalExceptionHandler;
import com.dailyai.assistant.exception.ResourceNotFoundException;
import com.dailyai.assistant.service.ArticleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class NewsControllerTest {

    @Mock
    private ArticleService articleService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new NewsController(articleService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void latestReturnsArticleList() throws Exception {
        ArticleDto article = new ArticleDto(1L, "AI update", "https://example.com/1", "desc",
                "Source", "Jane Doe", Instant.parse("2026-11-16T10:00:00Z"), "generative-ai",
                "Generative AI", null, false, null);
        when(articleService.latest(5)).thenReturn(List.of(article));

        mockMvc.perform(get("/api/news/latest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("AI update"))
                .andExpect(jsonPath("$[0].categoryName").value("Generative AI"))
                .andExpect(jsonPath("$[0].author").value("Jane Doe"));
    }

    @Test
    void missingArticleReturns404Json() throws Exception {
        when(articleService.get(99L)).thenThrow(new ResourceNotFoundException("Article 99 was not found"));

        mockMvc.perform(get("/api/news/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Article 99 was not found"))
                .andExpect(jsonPath("$.path").value("/api/news/99"));
    }
}
