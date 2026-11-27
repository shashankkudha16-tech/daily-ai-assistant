package com.dailyai.assistant.controller;

import com.dailyai.assistant.dto.ArticleDetailDto;
import com.dailyai.assistant.dto.ArticleDto;
import com.dailyai.assistant.dto.PageResponse;
import com.dailyai.assistant.service.ArticleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/news")
public class NewsController {

    private final ArticleService articleService;

    public NewsController(ArticleService articleService) {
        this.articleService = articleService;
    }

    @GetMapping
    public PageResponse<ArticleDto> list(@RequestParam(required = false) String category,
                                         @RequestParam(defaultValue = "newest") String sort,
                                         @RequestParam(required = false) String region,
                                         @RequestParam(required = false) String period,
                                         @RequestParam(defaultValue = "0") int page,
                                         @RequestParam(defaultValue = "12") int size) {
        return articleService.list(category, region, period, sort, page, size);
    }

    @GetMapping("/latest")
    public List<ArticleDto> latest(@RequestParam(defaultValue = "5") int limit) {
        return articleService.latest(limit);
    }

    @GetMapping("/search")
    public PageResponse<ArticleDto> search(@RequestParam(name = "q", required = false) String query,
                                           @RequestParam(required = false) String category,
                                           @RequestParam(defaultValue = "newest") String sort,
                                           @RequestParam(required = false) String region,
                                           @RequestParam(required = false) String period,
                                           @RequestParam(defaultValue = "0") int page,
                                           @RequestParam(defaultValue = "12") int size) {
        return articleService.search(query, category, region, period, sort, page, size);
    }

    @GetMapping("/category/{category}")
    public PageResponse<ArticleDto> byCategory(@PathVariable String category,
                                               @RequestParam(defaultValue = "newest") String sort,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "12") int size) {
        return articleService.list(category, sort, page, size);
    }

    @GetMapping("/{id}")
    public ArticleDetailDto detail(@PathVariable Long id) {
        return articleService.get(id);
    }
}
