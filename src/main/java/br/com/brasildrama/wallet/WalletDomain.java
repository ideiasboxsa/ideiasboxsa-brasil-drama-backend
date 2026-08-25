package br.com.brasildrama.wallet;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.*;

@Entity
@Table(name = "wallet_ledger")
class WalletLedgerEntry {
    @Id
    UUID id;

    @Column(name = "user_id", nullable = false)
    UUID userId;

    @Column(name = "operation_key", nullable = false, length = 160)
    String operationKey;

    @Column(name = "entry_type", nullable = false, length = 40)
    String entryType;

    @Column(nullable = false)
    int amount;

    @Column(name = "reference_type", length = 40)
    String referenceType;

    @Column(name = "reference_id", length = 160)
    String referenceId;

    @Column(name = "created_at", nullable = false)
    OffsetDateTime createdAt;

    protected WalletLedgerEntry() {}

    WalletLedgerEntry(UUID userId, String operationKey, String entryType, int amount, String referenceType, String referenceId) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.operationKey = operationKey;
        this.entryType = entryType;
        this.amount = amount;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.createdAt = OffsetDateTime.now();
    }
}

@Embeddable
class EpisodeEntitlementId implements Serializable {
    @Column(name = "user_id")
    UUID userId;

    @Column(name = "episode_id")
    UUID episodeId;

    protected EpisodeEntitlementId() {}

    EpisodeEntitlementId(UUID userId, UUID episodeId) {
        this.userId = userId;
        this.episodeId = episodeId;
    }

    @Override public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EpisodeEntitlementId other)) return false;
        return Objects.equals(userId, other.userId) && Objects.equals(episodeId, other.episodeId);
    }

    @Override public int hashCode() { return Objects.hash(userId, episodeId); }
}

@Entity
@Table(name = "episode_entitlement")
class EpisodeEntitlement {
    @EmbeddedId
    EpisodeEntitlementId id;

    @Column(nullable = false, length = 40)
    String source;

    @Column(name = "operation_key", nullable = false, length = 160)
    String operationKey;

    @Column(name = "granted_at", nullable = false)
    OffsetDateTime grantedAt;

    protected EpisodeEntitlement() {}

    EpisodeEntitlement(UUID userId, UUID episodeId, String source, String operationKey) {
        this.id = new EpisodeEntitlementId(userId, episodeId);
        this.source = source;
        this.operationKey = operationKey;
        this.grantedAt = OffsetDateTime.now();
    }
}

interface WalletLedgerRepository extends JpaRepository<WalletLedgerEntry, UUID> {
    Optional<WalletLedgerEntry> findByUserIdAndOperationKey(UUID userId, String operationKey);

    @Query("select coalesce(sum(e.amount), 0) from WalletLedgerEntry e where e.userId = :userId")
    long balance(@Param("userId") UUID userId);

    @Query(value = "select 1 from (select pg_advisory_xact_lock(hashtext(cast(:userId as text)))) wallet_lock", nativeQuery = true)
    int lockUser(@Param("userId") UUID userId);
}

interface EpisodeEntitlementRepository extends JpaRepository<EpisodeEntitlement, EpisodeEntitlementId> {
    List<EpisodeEntitlement> findAllByIdUserIdOrderByGrantedAtAsc(UUID userId);
    Optional<EpisodeEntitlement> findByIdUserIdAndOperationKey(UUID userId, String operationKey);
}
