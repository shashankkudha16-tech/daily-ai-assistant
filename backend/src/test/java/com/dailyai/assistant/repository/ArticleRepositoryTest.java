package com.dailyai.assistant.repository;

import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.Category;
import com.dailyai.assistant.entity.LearningTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.TestPropertySource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class ArticleRepositoryTest {

    @Autowired
    private ArticleRepository articleRepository;
    @Autowired
    private CategoryRepository categoryRepository;
    @Autowired
    private LearningTopicRepository topicRepository;

    @Test
    void storesAndFindsArticlesByUrlCategoryAndSearch() {
        Category category = categoryRepository.save(new Category("Generative AI", "generative-ai", null));

        Article article = new Article();
        article.setTitle("Open model update");
        article.setUrl("https://example.com/open-model");
        article.setDescription("A new open weight model for developers.");
        article.setSource("Example");
        article.setPublishedAt(Instant.parse("2026-11-16T10:00:00Z"));
        article.setCategory(category);
        articleRepository.save(article);

        assertThat(articleRepository.existsByUrl("https://example.com/open-model")).isTrue();
        assertThat(articleRepository.findByUrl("https://example.com/open-model")).isPresent();

        Page<Article> byCategory = articleRepository.findByCategorySlug("generative-ai", PageRequest.of(0, 10));
        assertThat(byCategory.getTotalElements()).isEqualTo(1);

        Page<Article> search = articleRepository.findAll(
                ArticleSpecifications.filters(null, null, null, "open weight"), PageRequest.of(0, 10));
        assertThat(search.getTotalElements()).isEqualTo(1);

        assertThat(articleRepository.findAll(ArticleSpecifications.filters(null, "GLOBAL", null, null),
                PageRequest.of(0, 10)).getTotalElements()).isZero();

        assertThat(articleRepository.countByCategorySlug("generative-ai")).isEqualTo(1);
    }

    @Test
    void pagedListingWorksWithTheServiceSort() {
        Category category = categoryRepository.save(new Category("AI Tools", "ai-tools", null));
        for (int index = 0; index < 3; index++) {
            Article article = new Article();
            article.setTitle("Article " + index);
            article.setUrl("https://example.com/article-" + index);
            article.setPublishedAt(Instant.parse("2026-11-1" + (index + 1) + "T10:00:00Z"));
            article.setCategory(category);
            articleRepository.save(article);
        }

        Sort sort = Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id"));
        Page<Article> page = articleRepository.findAll(PageRequest.of(0, 2, sort));

        assertThat(page.getTotalElements()).isEqualTo(3);
        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().get(0).getTitle()).isEqualTo("Article 2");
    }

    @Test
    void learningTopicsAreReadableByLevel() {
        LearningTopic topic = new LearningTopic();
        topic.setLevel(1);
        topic.setLevelName("Fundamentals");
        topic.setTitle("Introduction to AI");
        topic.setSlug("introduction-to-ai");
        topic.setOrderIndex(1);
        topicRepository.save(topic);

        assertThat(topicRepository.findByLevelOrderByOrderIndexAsc(1)).hasSize(1);
        assertThat(topicRepository.findBySlug("introduction-to-ai")).isPresent();
    }
}
