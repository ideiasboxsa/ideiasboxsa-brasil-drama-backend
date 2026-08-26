package br.com.brasildrama.admin;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admin_password_reset_token", indexes = {
    @Index(name = "idx_admin_password_reset_token_hash", columnList = "token_hash", unique = true),
    @Index(name = "idx_admin_password_reset_operator_created", columnList = "operator_id,created_at")
})
class AdminPasswordResetToken {
    @Id
    UUID id;

    @Column(name = "operator_id", nullable = false)
    UUID operatorId;

    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    String tokenHash;

    @Column(name = "expires_at", nullable = false)
    Instant expiresAt;

    @Column(name = "used_at")
    Instant usedAt;

    @Column(name = "created_at", nullable = false)
    Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}
