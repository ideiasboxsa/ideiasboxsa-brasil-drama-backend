package br.com.brasildrama.admin;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "admin_operator")
public class AdminOperator {
    @Id
    public UUID id;

    @Column(nullable = false, unique = true, length = 320)
    public String email;

    @Column(name = "display_name", nullable = false, length = 160)
    public String displayName;

    @Column(name = "password_hash", nullable = false, length = 100)
    public String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    public AdminRole role;

    @Column(nullable = false)
    public boolean active = true;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    void prePersist() {
        var now = Instant.now();
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        email = email == null ? null : email.trim().toLowerCase();
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
        email = email == null ? null : email.trim().toLowerCase();
    }
}

enum AdminRole {
    SUPER_ADMIN,
    CONTENT_ADMIN,
    EDITOR,
    FINANCE_ADMIN,
    SUPPORT,
    ANALYTICS_VIEWER
}
