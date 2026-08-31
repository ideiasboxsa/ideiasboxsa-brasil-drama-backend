package br.com.brasildrama.auth;

import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "app_user")
class UserAccount {
    @Id
    UUID id;

    @Column(nullable = false, unique = true, length = 320)
    String email;

    @Column(name = "display_name", length = 120)
    String displayName;

    @Column(name = "password_hash", length = 100)
    String passwordHash;

    /**
     * Claim {@code sub} do Google: identificador estável da conta, imune a troca
     * de e-mail. A busca no login social é por ele primeiro e só depois por
     * e-mail, para que quem trocar o endereço no Google continue entrando na
     * mesma conta em vez de ganhar uma nova.
     */
    @Column(name = "google_subject", length = 64, unique = true)
    String googleSubject;

    @Column(nullable = false)
    boolean autoplay = true;

    @Column(name = "allow_mobile_data", nullable = false)
    boolean allowMobileData = true;

    @Column(name = "created_at", nullable = false)
    OffsetDateTime createdAt;

    protected UserAccount() {}

    UserAccount(UUID id, String email, String displayName, String passwordHash) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.createdAt = OffsetDateTime.now();
    }
}
