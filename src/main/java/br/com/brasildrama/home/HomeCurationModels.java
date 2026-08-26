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
    @Column(name = "created_at", nullable = false) Instant createdAt;

    protected HomePlacementEntity() {}

    HomePlacementEntity(UUID dramaId, int position) {
        this.id = UUID.randomUUID();
        this.dramaId = dramaId;
        this.position = position;
        this.createdAt = Instant.now();
    }
}

interface HomePlacementRepository extends org.springframework.data.jpa.repository.JpaRepository<HomePlacementEntity, UUID> {
    List<HomePlacementEntity> findAllByOrderByPositionAsc();
}
