package com.dailyai.assistant.service;

import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.integration.ArticleSourceMaterial;
import com.dailyai.assistant.integration.PublisherArticleExtractor;
import com.dailyai.assistant.repository.ArticleRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublisherArticleContentResolutionTest {
    @Mock private ArticleRepository articles;
    @Mock private PublisherArticleExtractor extractor;

    @Test
    void persistsAndSelectsFreshlyExtractedPublisherContent() {
        Article article = article();
        article.setDescription("RSS excerpt remains separate.");
        var extracted = new ArticleSourceMaterial.ExtractedContent(
                ArticleSourceMaterial.Type.FULL_ARTICLE,
                ArticleSourceMaterial.Completeness.COMPLETE,
                "Verified publisher article text.");
        when(extractor.extractPublisherContent(article)).thenReturn(Optional.of(extracted));
        when(articles.save(article)).thenReturn(article);

        ArticleSourceMaterial selected = service().resolve(article);

        assertThat(selected.type()).isEqualTo(ArticleSourceMaterial.Type.FULL_ARTICLE);
        assertThat(selected.completeness()).isEqualTo(ArticleSourceMaterial.Completeness.COMPLETE);
        assertThat(selected.text()).isEqualTo("Verified publisher article text.");
        assertThat(article.getPublisherSourceContent()).isEqualTo("Verified publisher article text.");
        assertThat(article.getDescription()).isEqualTo("RSS excerpt remains separate.");
        verify(articles).save(article);
    }

    @Test
    void reusesStoredPublisherContentWithoutFetchingAgain() {
        Article article = article();
        article.setPublisherSourceContent("Previously verified publisher text.");
        article.setPublisherSourceType("EXCERPT");
        article.setPublisherSourceCompleteness("PARTIAL");

        ArticleSourceMaterial selected = service().resolve(article);

        assertThat(selected.type()).isEqualTo(ArticleSourceMaterial.Type.EXCERPT);
        assertThat(selected.text()).isEqualTo("Previously verified publisher text.");
        verify(extractor, never()).extractPublisherContent(article);
        verify(articles, never()).save(article);
    }

    @Test
    void fallsBackToRssOrExcerptWithoutPersistingItAsPublisherContent() {
        Article article = article();
        article.setRssContent("RSS body.");
        article.setRssContentAiAllowed(true);
        when(extractor.extractPublisherContent(article)).thenReturn(Optional.empty());

        ArticleSourceMaterial selected = service().resolve(article);

        assertThat(selected.type()).isEqualTo(ArticleSourceMaterial.Type.RSS_CONTENT);
        assertThat(selected.text()).isEqualTo("RSS body.");
        assertThat(article.getPublisherSourceContent()).isNull();
        verify(articles, never()).save(article);
    }

    private PublisherArticleContentPersistenceService service() {
        return new PublisherArticleContentPersistenceService(articles, extractor);
    }

    private Article article() {
        Article article = new Article();
        article.setTitle("Publisher article");
        article.setUrl("https://publisher.example/article");
        return article;
    }
}
