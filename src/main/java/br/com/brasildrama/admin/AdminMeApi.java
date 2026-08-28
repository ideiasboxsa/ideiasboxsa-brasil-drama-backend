package br.com.brasildrama.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
        var operator = currentOperator(principal);
        return operator == null
            ? ResponseEntity.status(401).build()
            : ResponseEntity.ok(profileOf(operator));
    }

    @PutMapping
    ResponseEntity<?> update(Principal principal, @RequestBody UpdateProfileRequest request) {
        var operator = currentOperator(principal);
        if (operator == null) return ResponseEntity.status(401).build();

        var displayName = request.displayName() == null ? "" : request.displayName().trim();
        if (displayName.length() < 2 || displayName.length() > 160) {
            return ResponseEntity.badRequest().body(new ValidationError("DISPLAY_NAME_INVALID"));
        }

        operator.displayName = displayName;
        operators.save(operator);
        return ResponseEntity.ok(profileOf(operator));
    }

    private AdminOperator currentOperator(Principal principal) {
        if (principal == null) return null;
        try {
            var id = UUID.fromString(principal.getName());
            return operators.findById(id)
                .filter(operator -> operator.active)
                .orElse(null);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private AdminProfile profileOf(AdminOperator operator) {
        return new AdminProfile(
            operator.id,
            operator.email,
            operator.displayName,
            operator.role.name(),
            operator.active,
            permissionsFor(operator.role),
            operator.createdAt,
            operator.updatedAt
        );
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

    record UpdateProfileRequest(String displayName) {}
    record ValidationError(String code) {}
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
