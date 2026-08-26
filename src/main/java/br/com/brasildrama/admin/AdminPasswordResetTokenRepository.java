package br.com.brasildrama.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface AdminPasswordResetTokenRepository extends JpaRepository<AdminPasswordResetToken, UUID> {
    Optional<AdminPasswordResetToken> findFirstByTokenHashAndUsedAtIsNullAndExpiresAtAfter(String tokenHash, Instant now);
    List<AdminPasswordResetToken> findAllByOperatorIdAndUsedAtIsNull(UUID operatorId);
}
