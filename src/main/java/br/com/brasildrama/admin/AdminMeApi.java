package br.com.brasildrama.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/me")
class AdminMeApi {
    private final AdminOperatorRepository operators;

    AdminMeApi(AdminOperatorRepository operators) {
        this.operators = operators;
    }

    @GetMapping
    ResponseEntity<?> me(Principal principal) {
        if (principal == null) return ResponseEntity.status(401).build();
        try {
            var id = UUID.fromString(principal.getName());
            return operators.findById(id)
                .filter(operator -> operator.active)
                .<ResponseEntity<?>>map(operator -> ResponseEntity.ok(new AdminProfile(
                    operator.id,
                    operator.email,
                    operator.displayName,
                    operator.role.name(),
                    operator.active,
                    permissionsFor(operator.role),
                    operator.createdAt,
                    operator.updatedAt
                )))
                .orElseGet(() -> ResponseEntity.status(401).build());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(401).build();
        }
    }

    private List<String> permissionsFor(AdminRole role) {
        return switch (role) {
            case SUPER_ADMIN -> List.of("STUDIO_ADMIN", "CONTENT_WRITE", "MONETIZATION_WRITE", "REWARDS_WRITE", "SUPPORT_WRITE", "ANALYTICS_READ", "OPERATIONS_READ");
            case CONTENT_ADMIN, EDITOR -> List.of("CONTENT_WRITE", "ANALYTICS_READ");
            case FINANCE_ADMIN -> List.of("MONETIZATION_WRITE", "ANALYTICS_READ");
            case SUPPORT -> List.of("SUPPORT_WRITE");
            case ANALYTICS_VIEWER -> List.of("ANALYTICS_READ");
        };
    }

    record AdminProfile(
        UUID id,
        String email,
        String displayName,
        String role,
        boolean active,
        List<String> permissions,
        Instant createdAt,
        Instant updatedAt
    ) {}
}
