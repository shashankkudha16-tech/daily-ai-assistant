package com.dailyai.assistant.repository;

import com.dailyai.assistant.entity.UserProgressEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface UserProgressEntryRepository extends JpaRepository<UserProgressEntry, Long> {
    Optional<UserProgressEntry> findByUserIdAndEntryDate(Long userId, LocalDate entryDate);
    List<UserProgressEntry> findTop20ByUserIdOrderByEntryDateDesc(Long userId);
    List<UserProgressEntry> findTop100ByUserIdOrderByEntryDateDesc(Long userId);
}
