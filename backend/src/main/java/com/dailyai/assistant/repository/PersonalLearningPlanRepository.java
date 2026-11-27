package com.dailyai.assistant.repository;

import com.dailyai.assistant.entity.PersonalLearningPlanItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface PersonalLearningPlanRepository extends JpaRepository<PersonalLearningPlanItem, Long> {
    List<PersonalLearningPlanItem> findByUserIdAndPlanDateOrderBySectionAscIdAsc(Long userId, LocalDate date);
    List<PersonalLearningPlanItem> findTop100ByUserIdOrderByPlanDateDescSectionAsc(Long userId);
    Optional<PersonalLearningPlanItem> findByIdAndUserId(Long id, Long userId);
    boolean existsByUserIdAndPlanDate(Long userId, LocalDate date);
}
