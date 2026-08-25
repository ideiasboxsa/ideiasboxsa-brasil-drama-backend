package br.com.brasildrama.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
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
                    operator.role.name()
                )))
                .orElseGet(() -> ResponseEntity.status(401).build());
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.status(401).build();
        }
    }

    record AdminProfile(UUID id, String email, String displayName, String role) {}
}
