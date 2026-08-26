package br.com.brasildrama.catalog;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "drama")
class DramaEntity {
    @Id UUID id;
    @Column(nullable = false) String title;
    @Column(nullable = false, columnDefinition = "text") String synopsis;
    @Column(nullable = false) String genre;
    @Column(name = "cover_url", columnDefinition = "text") String coverUrl;
    @Column(name = "poster_object_key", length = 512) String posterObjectKey;
    @Column(name = "backdrop_object_key", length = 512) String backdropObjectKey;
    @Column(unique = true, length = 180) String slug;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24) DramaStatus status = DramaStatus.PUBLISHED;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;

    protected DramaEntity() {}

    @PrePersist
    void prePersist() {
        var now = Instant.now();
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
        if (status == null) status = DramaStatus.DRAFT;
    }

    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
}

enum DramaStatus { DRAFT, READY, PUBLISHED, ARCHIVED }

@Entity
@Table(name = "episode", uniqueConstraints = @UniqueConstraint(name = "uk_episode_drama_number", columnNames = {"drama_id", "number"}))
class EpisodeEntity {
    @Id UUID id;
    @Column(name = "drama_id", nullable = false) UUID dramaId;
    @Column(nullable = false) int number;
    @Column(nullable = false) String title;
    @Column(columnDefinition = "text") String description;
    @Column(name = "duration_seconds") Integer durationSeconds;
    @Column(name = "coin_price", nullable = false) int coinPrice;
    @Column(nullable = false) boolean free;
    @Column(name = "video_url", columnDefinition = "text") String videoUrl;
    @Column(name = "video_object_key", length = 512) String videoObjectKey;
    @Column(name = "created_at", nullable = false) Instant createdAt;
    @Column(name = "updated_at", nullable = false) Instant updatedAt;

    protected EpisodeEntity() {}

    @PrePersist
    void prePersist() {
        var now = Instant.now();
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate void preUpdate() { updatedAt = Instant.now(); }
}

interface DramaRepository extends org.springframework.data.jpa.repository.JpaRepository<DramaEntity, UUID> {
    java.util.List<DramaEntity> findAllByOrderByTitleAsc();
    java.util.List<DramaEntity> findByStatusOrderByTitleAsc(DramaStatus status);
    java.util.List<DramaEntity> findByTitleContainingIgnoreCaseOrSynopsisContainingIgnoreCaseOrGenreContainingIgnoreCaseOrderByTitleAsc(String title, String synopsis, String genre);
    java.util.List<DramaEntity> findByGenreIgnoreCaseOrderByTitleAsc(String genre);
    boolean existsBySlugIgnoreCase(String slug);
}

interface EpisodeRepository extends org.springframework.data.jpa.repository.JpaRepository<EpisodeEntity, UUID> {
    java.util.List<EpisodeEntity> findByDramaIdOrderByNumberAsc(UUID dramaId);
    boolean existsByDramaIdAndNumber(UUID dramaId, int number);
}
