package com.dailyai.assistant.repository;

import com.dailyai.assistant.entity.GuestMigration;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GuestMigrationRepository extends JpaRepository<GuestMigration, String> { }
