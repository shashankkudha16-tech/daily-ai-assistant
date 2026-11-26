package com.dailyai.assistant.service;

import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.integration.ArticleSourceMaterial;
import com.dailyai.assistant.integration.PublisherArticleExtractor;
import com.dailyai.assistant.repository.ArticleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublisherArticleContentPersistenceService {
    private final ArticleRepository articleRepository;
    private final PublisherArticleExtractor extractor;

    public PublisherArticleContentPersistenceService(ArticleRepository articleRepository,
                                                     PublisherArticleExtractor extractor) {
        this.articleRepository = articleRepository;
        this.extractor = extractor;
    }

    @Transactional
    public ArticleSourceMaterial resolve(Article article) {
        var storedPublisherContent = ArticleSourceMaterial.fromStoredPublisherContent(article);
        if (storedPublisherContent.isPresent()) return storedPublisherContent.get();

        var extracted = extractor.extractPublisherContent(article);
        if (extracted.isPresent()) {
            ArticleSourceMaterial source = new ArticleSourceMaterial(extracted.get().type(),
                    extracted.get().completeness(), extracted.get().text());
            Article saved = persist(article, source);
            return ArticleSourceMaterial.fromStoredPublisherContent(saved).orElse(source);
        }
        return ArticleSourceMaterial.fromFeed(article);
    }

    @Transactional
    public Article persist(Article article, ArticleSourceMaterial source) {
        if (article == null || source == null || !ArticleSourceMaterial.hasText(source.text())) {
            throw new IllegalArgumentException("Publisher extraction and article are required");
        }
        boolean completeArticle = source.type() == ArticleSourceMaterial.Type.FULL_ARTICLE
                && source.completeness() == ArticleSourceMaterial.Completeness.COMPLETE;
        boolean excerpt = source.type() == ArticleSourceMaterial.Type.EXCERPT
                && (source.completeness() == ArticleSourceMaterial.Completeness.PARTIAL
                || source.completeness() == ArticleSourceMaterial.Completeness.TRUNCATED);
        if (!completeArticle && !excerpt) {
            throw new IllegalArgumentException("Only validated publisher article or excerpt content can be persisted");
        }
        article.setPublisherSourceContent(source.text().trim());
        article.setPublisherSourceType(source.type().name());
        article.setPublisherSourceCompleteness(source.completeness().name());
        return articleRepository.save(article);
    }
}
