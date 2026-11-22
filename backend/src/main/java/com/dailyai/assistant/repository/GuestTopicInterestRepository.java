package com.dailyai.assistant.repository;

import com.dailyai.assistant.entity.GuestTopicInterest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface GuestTopicInterestRepository extends JpaRepository<GuestTopicInterest, Long> {
    Optional<GuestTopicInterest> findByGuestIdAndTopic(String guestId, String topic);
    List<GuestTopicInterest> findByGuestId(String guestId);
    void deleteByGuestId(String guestId);
}
