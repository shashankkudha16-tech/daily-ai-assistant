package com.dailyai.assistant.service;

import com.dailyai.assistant.dto.GuestInteractionRequest;
import com.dailyai.assistant.entity.Article;
import com.dailyai.assistant.entity.Category;
import com.dailyai.assistant.entity.GuestArticleInteraction;
import com.dailyai.assistant.entity.GuestTopicInterest;
import com.dailyai.assistant.repository.ArticleRepository;
import com.dailyai.assistant.repository.CategoryRepository;
import com.dailyai.assistant.repository.GuestArticleInteractionRepository;
import com.dailyai.assistant.repository.GuestTopicInterestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GuestPersonalizationServiceTest {
    @Autowired GuestPersonalizationService service;
    @Autowired ArticleRepository articleRepository;
    @Autowired CategoryRepository categoryRepository;
    @Autowired GuestArticleInteractionRepository interactionRepository;
    @Autowired GuestTopicInterestRepository interestRepository;

    private String guestA;
    private String guestB;
    private Category generativeAi;

    @BeforeEach
    void setUp() {
        guestA = UUID.randomUUID().toString();
        guestB = UUID.randomUUID().toString();
        generativeAi = categoryRepository.save(new Category("Generative AI", "generative-ai-" + guestA.substring(0, 8), null));
    }

    @Test
    void openIsWeakAndMeaningfulViewAddsMoreInterest() {
        Article article = saveArticle("A generative AI model update", generativeAi);
        service.recordInteraction(guestA, new GuestInteractionRequest(article.getId(), GuestInteractionRequest.Type.OPEN));
        double afterOpen = interestRepository.findByGuestId(guestA).get(0).getScore();
        service.recordInteraction(guestA, new GuestInteractionRequest(article.getId(), GuestInteractionRequest.Type.QUALIFIED_VIEW));
        double afterView = interestRepository.findByGuestId(guestA).get(0).getScore();

        assertThat(afterOpen).isEqualTo(0.20);
        assertThat(afterView).isEqualTo(1.45);
        assertThat(interactionRepository.findByGuestId(guestA).get(0).getOpenCount()).isEqualTo(1);
        assertThat(interactionRepository.findByGuestId(guestA).get(0).getQualifiedViewCount()).isEqualTo(1);
    }

    @Test
    void searchUsesTopicLabelsWithoutStoringRawQueryAndGuestScoresAreIsolated() {
        service.recordSearch(guestA, "best cybersecurity AI tools");
        assertThat(interestRepository.findByGuestId(guestA).stream().map(GuestTopicInterest::getTopic))
                .contains("cybersecurity", "ai-tools");
        assertThat(service.scoresForTesting(guestB)).isEmpty();
        assertThat(interestRepository.findByGuestId(guestB)).isEmpty();
    }

    @Test
    void topicScoresDecayWithThirtyDayHalfLife() {
        GuestTopicInterest interest = new GuestTopicInterest();
        interest.setGuestId(guestA);
        interest.setTopic("generative-ai");
        interest.setScore(2.0);
        interest.setUpdatedAt(Instant.now().minus(30, ChronoUnit.DAYS));
        interestRepository.save(interest);

        assertThat(service.scoresForTesting(guestA).get("generative-ai")).isBetween(0.99, 1.01);
    }

    @Test
    void oneArticleOpenIsTooWeakToPersonalizeTheFeed() {
        Article article = saveArticle("Generative AI model update", generativeAi);
        service.recordInteraction(guestA, new GuestInteractionRequest(article.getId(), GuestInteractionRequest.Type.OPEN));

        var recommendations = service.recommendations(guestA, 12);

        assertThat(recommendations.personalized()).isFalse();
        assertThat(recommendations.message()).contains("improve as you explore");
    }

    @Test
    void recommendationsPreferMatchingTopicsThenExploreAndExcludeSeenOrRepeatedItems() {
        Article interestStory = saveArticle("Generative AI model launch", generativeAi);
        Article newTopic = saveArticle("Robotics research update", null);
        Article alreadyRead = saveArticle("Another generative AI paper", generativeAi);
        service.recordSearch(guestA, "generative AI model");
        service.recordInteraction(guestA, new GuestInteractionRequest(alreadyRead.getId(), GuestInteractionRequest.Type.OPEN));

        var first = service.recommendations(guestA, 12);
        var ids = first.articles().stream().map(article -> article.id()).toList();
        assertThat(ids).contains(interestStory.getId(), newTopic.getId()).doesNotContain(alreadyRead.getId());
        assertThat(new HashSet<>(ids)).hasSameSizeAs(ids);
        assertThat(first.personalized()).isTrue();

        var second = service.recommendations(guestA, 12);
        assertThat(second.articles()).extracting(value -> value.id()).doesNotContainAnyElementsOf(ids);
    }

    @Test
    void newGuestGetsRecentFallbackAndResetClearsOnlyThatGuest() {
        Article article = saveArticle("New AI research article", null);
        service.recordSearch(guestA, "cybersecurity");
        service.recordSearch(guestB, "machine learning");
        var fallback = service.recommendations(UUID.randomUUID().toString(), 12);
        assertThat(fallback.personalized()).isFalse();
        assertThat(fallback.message()).contains("improve as you explore");
        assertThat(fallback.articles()).extracting(value -> value.id()).contains(article.getId());

        service.reset(guestA);
        assertThat(interestRepository.findByGuestId(guestA)).isEmpty();
        assertThat(interactionRepository.findByGuestId(guestA)).isEmpty();
        assertThat(interestRepository.findByGuestId(guestB)).hasSize(1);
    }

    private Article saveArticle(String title, Category category) {
        Article article = new Article();
        article.setTitle(title);
        article.setUrl("https://example.com/guest/" + UUID.randomUUID());
        article.setDescription(title + " provides an update for readers.");
        article.setPublishedAt(Instant.now().plusMillis(articleRepository.count() + 1));
        article.setCategory(category);
        return articleRepository.save(article);
    }
}
