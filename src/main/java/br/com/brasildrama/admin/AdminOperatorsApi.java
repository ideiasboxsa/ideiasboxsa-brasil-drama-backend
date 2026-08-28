package br.com.brasildrama.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import java.security.Principal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/operators")
class AdminOperatorsApi {
    private final AdminOperatorRepository operators;

    AdminOperatorsApi(AdminOperatorRepository operators) {
        this.operators = operators;
    }

    @GetMapping
    ResponseEntity<?> list(Principal principal) {
        var actor = superAdmin(principal);
        if (actor == null) return ResponseEntity.status(403).build();

        var result = operators.findAll().stream()
            .sorted(Comparator.comparing((AdminOperator value) -> value.active).reversed()
                .thenComparing(value -> value.displayName == null ? "" : value.displayName, String.CASE_INSENSITIVE_ORDER))
            .map(this::view)
            .toList();
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{operatorId}")
    ResponseEntity<?> update(
        @PathVariable UUID operatorId,
        @RequestBody UpdateOperatorRequest request,
        Principal principal
    ) {
        var actor = superAdmin(principal);
        if (actor == null) return ResponseEntity.status(403).build();
        if (actor.id.equals(operatorId)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("SELF_ACCESS_CHANGE_BLOCKED"));
        }

        var target = operators.findById(operatorId).orElse(null);
        if (target == null) return ResponseEntity.notFound().build();

        if (request.role() != null && !request.role().isBlank()) {
            try {
                target.role = AdminRole.valueOf(request.role().trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return ResponseEntity.badRequest().body(new ErrorResponse("ADMIN_ROLE_INVALID"));
            }
        }
        if (request.active() != null) target.active = request.active();
        target.updatedAt = Instant.now();
        operators.save(target);
        return ResponseEntity.ok(view(target));
    }

    private AdminOperator superAdmin(Principal principal) {
        if (principal == null) return null;
        try {
            return operators.findById(UUID.fromString(principal.getName()))
                .filter(value -> value.active && value.role == AdminRole.SUPER_ADMIN)
                .orElse(null);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private OperatorView view(AdminOperator value) {
        return new OperatorView(
            value.id,
            value.email,
            value.displayName,
            value.role.name(),
            value.active,
            value.createdAt,
            value.updatedAt
        );
    }

    record UpdateOperatorRequest(String role, Boolean active) {}
    record ErrorResponse(String code) {}
    record OperatorView(
        UUID id,
        String email,
        String displayName,
        String role,
        boolean active,
        Instant createdAt,
        Instant updatedAt
    ) {}
}
