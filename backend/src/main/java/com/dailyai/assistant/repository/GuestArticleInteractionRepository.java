package com.dailyai.assistant.repository;

import com.dailyai.assistant.entity.GuestArticleInteraction;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface GuestArticleInteractionRepository extends JpaRepository<GuestArticleInteraction, Long> {
    Optional<GuestArticleInteraction> findByGuestIdAndArticleId(String guestId, Long articleId);
    List<GuestArticleInteraction> findByGuestId(String guestId);
    List<GuestArticleInteraction> findByGuestIdAndOpenedAtIsNotNull(String guestId);
    void deleteByGuestId(String guestId);
}
