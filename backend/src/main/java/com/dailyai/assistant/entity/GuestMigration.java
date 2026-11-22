package com.dailyai.assistant.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "guest_migrations", indexes = @Index(name = "idx_guest_migrations_user", columnList = "user_id"))
public class GuestMigration {
    @Id
    @Column(name = "guest_id", length = 36)
    private String guestId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
    @Column(name = "migrated_at", nullable = false)
    private Instant migratedAt;

    public String getGuestId() { return guestId; }
    public void setGuestId(String guestId) { this.guestId = guestId; }
    public User getUser() { return user; }
    public void setUser(User user) { this.user = user; }
    public Instant getMigratedAt() { return migratedAt; }
    public void setMigratedAt(Instant migratedAt) { this.migratedAt = migratedAt; }
}
