package com.dailyai.assistant.integration;

import com.dailyai.assistant.entity.Article;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ArticleSourceMaterialTest {
    @Test
    void usesOnlyAiPermittedRssContentAndPreservesItsCompleteness() {
        Article article = article();
        article.setRssContent("Publisher full-content field.");

        ArticleSourceMaterial conservative = ArticleSourceMaterial.fromFeed(article);
        assertThat(conservative.type()).isEqualTo(ArticleSourceMaterial.Type.EXCERPT);
        assertThat(conservative.text()).isEqualTo("Short publisher excerpt.");

        article.setRssContentAiAllowed(true);
        ArticleSourceMaterial permitted = ArticleSourceMaterial.fromFeed(article);
        assertThat(permitted.type()).isEqualTo(ArticleSourceMaterial.Type.RSS_CONTENT);
        assertThat(permitted.completeness()).isEqualTo(ArticleSourceMaterial.Completeness.PARTIAL);
        assertThat(permitted.text()).contains("Publisher full-content field.");

        article.setRssContentTruncated(true);
        assertThat(ArticleSourceMaterial.fromFeed(article).completeness())
                .isEqualTo(ArticleSourceMaterial.Completeness.TRUNCATED);
    }

    @Test
    void distinguishesCompletePublisherPageFromTruncatedAndFallbackContent() {
        Article article = article();
        var completePage = new ArticleSourceMaterial.ExtractedContent(
                ArticleSourceMaterial.Type.FULL_ARTICLE, ArticleSourceMaterial.Completeness.COMPLETE,
                "Complete extracted article.");
        assertThat(ArticleSourceMaterial.choose(article, completePage).type())
                .isEqualTo(ArticleSourceMaterial.Type.FULL_ARTICLE);

        var partialPage = new ArticleSourceMaterial.ExtractedContent(
                ArticleSourceMaterial.Type.EXCERPT, ArticleSourceMaterial.Completeness.TRUNCATED,
                "Cut extracted article text.");
        ArticleSourceMaterial truncated = ArticleSourceMaterial.choose(article, partialPage);
        assertThat(truncated.type()).isEqualTo(ArticleSourceMaterial.Type.EXCERPT);
        assertThat(truncated.completeness()).isEqualTo(ArticleSourceMaterial.Completeness.TRUNCATED);

        article.setDescription(" ");
        assertThat(ArticleSourceMaterial.choose(article, null).type()).isEqualTo(ArticleSourceMaterial.Type.TITLE_ONLY);
    }

    @Test
    void persistedPublisherContentIsDistinctAndTakesPrecedenceOverRssPermission() {
        Article article = article();
        article.setRssContent("RSS body remains separate.");
        article.setRssContentAiAllowed(true);
        article.setPublisherSourceContent("Extracted publisher body.");
        article.setPublisherSourceType("FULL_ARTICLE");
        article.setPublisherSourceCompleteness("COMPLETE");

        ArticleSourceMaterial selected = ArticleSourceMaterial.fromFeed(article);

        assertThat(selected.type()).isEqualTo(ArticleSourceMaterial.Type.FULL_ARTICLE);
        assertThat(selected.completeness()).isEqualTo(ArticleSourceMaterial.Completeness.COMPLETE);
        assertThat(selected.text()).isEqualTo("Extracted publisher body.");
        assertThat(article.getRssContent()).isEqualTo("RSS body remains separate.");
    }

    @Test
    void persistedTruncatedPublisherExcerptKeepsItsStatus() {
        Article article = article();
        article.setPublisherSourceContent("Truncated publisher excerpt.");
        article.setPublisherSourceType("EXCERPT");
        article.setPublisherSourceCompleteness("TRUNCATED");

        ArticleSourceMaterial selected = ArticleSourceMaterial.fromFeed(article);

        assertThat(selected.type()).isEqualTo(ArticleSourceMaterial.Type.EXCERPT);
        assertThat(selected.completeness()).isEqualTo(ArticleSourceMaterial.Completeness.TRUNCATED);
    }

    private Article article() {
        Article article = new Article();
        article.setTitle("A headline");
        article.setDescription("Short publisher excerpt.");
        return article;
    }
}
