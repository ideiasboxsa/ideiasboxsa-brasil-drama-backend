package br.com.brasildrama.catalog;

import jakarta.persistence.*;
import java.util.*;

@Entity
@Table(name = "drama")
class DramaEntity {
    @Id UUID id;
    @Column(nullable = false) String title;
    @Column(nullable = false, columnDefinition = "text") String synopsis;
    @Column(nullable = false) String genre;
    @Column(name = "cover_url", columnDefinition = "text") String coverUrl;

    protected DramaEntity() {}
}

@Entity
@Table(name = "episode", uniqueConstraints = @UniqueConstraint(name = "uk_episode_drama_number", columnNames = {"drama_id", "number"}))
class EpisodeEntity {
    @Id UUID id;
    @Column(name = "drama_id", nullable = false) UUID dramaId;
    @Column(nullable = false) int number;
    @Column(nullable = false) String title;
    @Column(name = "coin_price", nullable = false) int coinPrice;
    @Column(nullable = false) boolean free;
    @Column(name = "video_url", nullable = false, columnDefinition = "text") String videoUrl;

    protected EpisodeEntity() {}
}

interface DramaRepository extends org.springframework.data.jpa.repository.JpaRepository<DramaEntity, UUID> {
    java.util.List<DramaEntity> findAllByOrderByTitleAsc();
    java.util.List<DramaEntity> findByTitleContainingIgnoreCaseOrSynopsisContainingIgnoreCaseOrGenreContainingIgnoreCaseOrderByTitleAsc(String title, String synopsis, String genre);
    java.util.List<DramaEntity> findByGenreIgnoreCaseOrderByTitleAsc(String genre);
}

interface EpisodeRepository extends org.springframework.data.jpa.repository.JpaRepository<EpisodeEntity, UUID> {
    java.util.List<EpisodeEntity> findByDramaIdOrderByNumberAsc(UUID dramaId);
}
