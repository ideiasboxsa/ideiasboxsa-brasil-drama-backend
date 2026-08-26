package br.com.brasildrama.home;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(name = "home_placement")
class HomePlacementEntity {
    @Id UUID id;
    @Column(name = "drama_id", nullable = false) UUID dramaId;
    @Column(nullable = false) int position;
    @Column(name = "section_key", nullable = false, length = 80) String sectionKey;
    @Column(name = "section_title", nullable = false, length = 120) String sectionTitle;
    @Column(name = "section_position", nullable = false) int sectionPosition;
    @Column(name = "is_hero", nullable = false) boolean hero;
    @Column(name = "created_at", nullable = false) Instant createdAt;

    protected HomePlacementEntity() {}

    HomePlacementEntity(UUID dramaId, int position, String sectionKey, String sectionTitle, int sectionPosition, boolean hero) {
        this.id = UUID.randomUUID();
        this.dramaId = dramaId;
        this.position = position;
        this.sectionKey = sectionKey;
        this.sectionTitle = sectionTitle;
        this.sectionPosition = sectionPosition;
        this.hero = hero;
        this.createdAt = Instant.now();
    }
}

interface HomePlacementRepository extends org.springframework.data.jpa.repository.JpaRepository<HomePlacementEntity, UUID> {
    List<HomePlacementEntity> findAllByOrderBySectionPositionAscPositionAsc();
}
