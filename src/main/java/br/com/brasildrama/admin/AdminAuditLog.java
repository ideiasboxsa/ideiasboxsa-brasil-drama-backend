package br.com.brasildrama.admin;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "admin_audit_log")
class AdminAuditLog {
    @Id UUID id;
    @Column(name = "actor_operator_id", nullable = false) UUID actorOperatorId;
    @Column(nullable = false, length = 80) String action;
    @Column(name = "entity_type", nullable = false, length = 80) String entityType;
    @Column(name = "entity_id", length = 160) String entityId;
    @Column(nullable = false, length = 500) String summary;
    @Column(name = "created_at", nullable = false) Instant createdAt;

    @PrePersist void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = Instant.now();
    }
}

interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {
    List<AdminAuditLog> findAllByOrderByCreatedAtDesc(PageRequest page);
}

@Service
class AdminAuditService {
    private final AdminAuditLogRepository logs;
    AdminAuditService(AdminAuditLogRepository logs) { this.logs = logs; }

    void record(UUID actorId, String action, String entityType, String entityId, String summary) {
        var log = new AdminAuditLog();
        log.actorOperatorId = actorId;
        log.action = action;
        log.entityType = entityType;
        log.entityId = entityId;
        log.summary = summary;
        logs.save(log);
    }
}

@RestController
@RequestMapping("/v1/admin/audit")
class AdminAuditApi {
    private final AdminOperatorRepository operators;
    private final AdminAuditLogRepository logs;

    AdminAuditApi(AdminOperatorRepository operators, AdminAuditLogRepository logs) {
        this.operators = operators;
        this.logs = logs;
    }

    @GetMapping
    ResponseEntity<?> latest(Principal principal) {
        if (principal == null) return ResponseEntity.status(403).build();
        AdminOperator actor;
        try {
            actor = operators.findById(UUID.fromString(principal.getName())).orElse(null);
        } catch (IllegalArgumentException ex) {
            actor = null;
        }
        if (actor == null || !actor.active || actor.role != AdminRole.SUPER_ADMIN) return ResponseEntity.status(403).build();
        var result = logs.findAllByOrderByCreatedAtDesc(PageRequest.of(0, 100)).stream()
            .map(log -> new AuditView(log.id, log.actorOperatorId, log.action, log.entityType, log.entityId, log.summary, log.createdAt))
            .toList();
        return ResponseEntity.ok(result);
    }

    record AuditView(UUID id, UUID actorOperatorId, String action, String entityType, String entityId, String summary, Instant createdAt) {}
}
