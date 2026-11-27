package com.dailyai.assistant.repository;

import com.dailyai.assistant.entity.MentorReview;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface MentorReviewRepository extends JpaRepository<MentorReview, Long> {
    Optional<MentorReview> findByUserIdAndReviewDate(Long userId, LocalDate reviewDate);
    List<MentorReview> findTop20ByUserIdOrderByReviewDateDesc(Long userId);
}
