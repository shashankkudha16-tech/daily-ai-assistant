package com.dailyai.assistant.repository;

import com.dailyai.assistant.entity.UserTopicInterest;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface UserTopicInterestRepository extends JpaRepository<UserTopicInterest, Long> {
    Optional<UserTopicInterest> findByUserIdAndTopic(Long userId, String topic);
    List<UserTopicInterest> findByUserId(Long userId);
}
