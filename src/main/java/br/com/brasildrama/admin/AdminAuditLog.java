package br.com.brasildrama.admin;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Entity @Table(name="admin_audit_log")
class AdminAuditLog {
    @Id UUID id;
    @Column(name="actor_operator_id",nullable=false) UUID actorOperatorId;
    @Column(nullable=false,length=80) String action;
    @Column(name="entity_type",nullable=false,length=80) String entityType;
    @Column(name="entity_id",length=160) String entityId;
    @Column(nullable=false,length=500) String summary;
    @Column(name="created_at",nullable=false) Instant createdAt;
    @PrePersist void prePersist(){if(id==null)id=UUID.randomUUID();if(createdAt==null)createdAt=Instant.now();}
}
interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog,UUID>{List<AdminAuditLog> findAllByOrderByCreatedAtDesc(PageRequest page);}
@Service class AdminAuditService {
    private final AdminAuditLogRepository logs; AdminAuditService(AdminAuditLogRepository logs){this.logs=logs;}
    void record(UUID actorId,String action,String entityType,String entityId,String summary){var log=new AdminAuditLog();log.actorOperatorId=actorId;log.action=action;log.entityType=entityType;log.entityId=entityId;log.summary=summary;logs.save(log);}
}
@RestController @RequestMapping("/v1/admin/audit")
class AdminAuditApi {
    private final AdminOperatorRepository operators; private final AdminAuditLogRepository logs;
    AdminAuditApi(AdminOperatorRepository operators,AdminAuditLogRepository logs){this.operators=operators;this.logs=logs;}

    @GetMapping ResponseEntity<?> latest(
        Principal principal,
        @RequestParam(required=false) String action,
        @RequestParam(required=false) UUID actorOperatorId
    ){
        var actor=currentSuperAdmin(principal);
        if(actor==null)return ResponseEntity.status(403).build();

        var normalizedAction=action==null?null:action.trim().toUpperCase(Locale.ROOT);
        var raw=logs.findAllByOrderByCreatedAtDesc(PageRequest.of(0,100));
        var actorIds=raw.stream().map(log->log.actorOperatorId).collect(Collectors.toSet());
        var actors=operators.findAllById(actorIds).stream().collect(Collectors.toMap(operator->operator.id, Function.identity()));

        var result=raw.stream()
            .filter(log->normalizedAction==null||normalizedAction.isBlank()||normalizedAction.equals(log.action))
            .filter(log->actorOperatorId==null||actorOperatorId.equals(log.actorOperatorId))
            .map(log->{
                var source=actors.get(log.actorOperatorId);
                return new AuditView(log.id,log.actorOperatorId,source==null?"Operador":source.displayName,source==null?null:source.email,log.action,log.entityType,log.entityId,log.summary,log.createdAt);
            }).toList();
        return ResponseEntity.ok(result);
    }

    private AdminOperator currentSuperAdmin(Principal principal){
        if(principal==null)return null;
        try{return operators.findById(UUID.fromString(principal.getName())).filter(value->value.active&&value.role==AdminRole.SUPER_ADMIN).orElse(null);}
        catch(IllegalArgumentException ex){return null;}
    }

    record AuditView(UUID id,UUID actorOperatorId,String actorDisplayName,String actorEmail,String action,String entityType,String entityId,String summary,Instant createdAt){}
}
