package br.com.brasildrama.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.Arrays;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/operator-roles")
class AdminOperatorRolesApi {
    private final AdminOperatorRepository operators;

    AdminOperatorRolesApi(AdminOperatorRepository operators) {
        this.operators = operators;
    }

    @GetMapping
    ResponseEntity<?> list(Principal principal) {
        if (!isSuperAdmin(principal)) return ResponseEntity.status(403).build();
        return ResponseEntity.ok(
            Arrays.stream(AdminRole.values())
                .map(role -> new RoleView(role.name(), label(role)))
                .toList()
        );
    }

    private boolean isSuperAdmin(Principal principal) {
        if (principal == null) return false;
        try {
            return operators.findById(UUID.fromString(principal.getName()))
                .filter(value -> value.active && value.role == AdminRole.SUPER_ADMIN)
                .isPresent();
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private String label(AdminRole role) {
        return switch (role) {
            case SUPER_ADMIN -> "Super Admin";
            case CONTENT_ADMIN -> "Admin. Conteúdo";
            case EDITOR -> "Editor";
            case FINANCE_ADMIN -> "Admin. Financeiro";
            case SUPPORT -> "Atendimento";
            case ANALYTICS_VIEWER -> "Analytics";
        };
    }

    record RoleView(String code, String label) {}
}
