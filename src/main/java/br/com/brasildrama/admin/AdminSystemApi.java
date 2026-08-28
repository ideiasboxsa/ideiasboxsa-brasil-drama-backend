package br.com.brasildrama.admin;

import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/v1/admin/system")
class AdminSystemApi {
    private final AdminOperatorRepository operators;
    private final HealthEndpoint health;

    AdminSystemApi(AdminOperatorRepository operators, HealthEndpoint health) {
        this.operators = operators;
        this.health = health;
    }

    @GetMapping("/health")
    ResponseEntity<?> health(Principal principal) {
        var actor = superAdmin(principal);
        if (actor == null) return ResponseEntity.status(403).build();
        var aggregate = health.health();
        var status = aggregate.getStatus().getCode();
        return ResponseEntity.ok(new SystemHealthView(
            "brasil-drama-backend",
            status,
            "UP".equalsIgnoreCase(status),
            Instant.now(),
            "Spring Boot Actuator aggregate; detalhes sensíveis não são expostos"
        ));
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

    record SystemHealthView(String service, String status, boolean healthy, Instant checkedAt, String source) {}
}
