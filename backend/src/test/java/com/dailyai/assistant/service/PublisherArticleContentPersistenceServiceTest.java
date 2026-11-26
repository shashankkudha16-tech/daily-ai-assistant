package com.dailyai.assistant.service;

import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.integration.ArticleSourceMaterial;
import com.dailyai.assistant.repository.ArticleRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({PublisherArticleContentPersistenceService.class, com.dailyai.assistant.integration.PublisherArticleExtractor.class})
@TestPropertySource(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PublisherArticleContentPersistenceServiceTest {
    @Autowired private ArticleRepository articleRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PublisherArticleContentPersistenceService persistenceService;

    @Test
    void persistsPublisherBodyAndCompletenessWithoutChangingRssFields() {
        Article article = article();
        article.setRssContent("Existing RSS data.");
        article.setRssContentAiAllowed(false);
        article.setRssContentTruncated(true);
        articleRepository.saveAndFlush(article);

        Article saved = persistenceService.persist(article, new ArticleSourceMaterial(
                ArticleSourceMaterial.Type.FULL_ARTICLE,
                ArticleSourceMaterial.Completeness.COMPLETE,
                "Verified publisher article body."));
        entityManager.flush();
        entityManager.clear();

        Article reloaded = articleRepository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getPublisherSourceContent()).isEqualTo("Verified publisher article body.");
        assertThat(reloaded.getPublisherSourceType()).isEqualTo("FULL_ARTICLE");
        assertThat(reloaded.getPublisherSourceCompleteness()).isEqualTo("COMPLETE");
        assertThat(reloaded.getRssContent()).isEqualTo("Existing RSS data.");
        assertThat(reloaded.isRssContentAiAllowed()).isFalse();
        assertThat(reloaded.isRssContentTruncated()).isTrue();
        assertThat(ArticleSourceMaterial.fromFeed(reloaded).text()).isEqualTo("Verified publisher article body.");
    }

    @Test
    void persistsTruncatedExcerptAsExcerptAndDoesNotAcceptRssAsPublisherContent() {
        Article article = article();
        article.setUrl("https://example.com/truncated");
        Article saved = persistenceService.persist(article, new ArticleSourceMaterial(
                ArticleSourceMaterial.Type.EXCERPT,
                ArticleSourceMaterial.Completeness.TRUNCATED,
                "Partial publisher text."));

        assertThat(saved.getPublisherSourceType()).isEqualTo("EXCERPT");
        assertThat(saved.getPublisherSourceCompleteness()).isEqualTo("TRUNCATED");
        assertThatThrownBy(() -> persistenceService.persist(article, new ArticleSourceMaterial(
                ArticleSourceMaterial.Type.RSS_CONTENT,
                ArticleSourceMaterial.Completeness.PARTIAL,
                "RSS body")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Article article() {
        Article article = new Article();
        article.setTitle("Publisher article");
        article.setUrl("https://example.com/article");
        return article;
    }
}
