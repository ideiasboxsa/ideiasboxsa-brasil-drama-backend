package br.com.brasildrama.admin;

import br.com.brasildrama.auth.JwtService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@RestController
@RequestMapping("/v1/admin/auth")
public class AdminAuthApi {
    private final AdminOperatorRepository operators;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AdminAuthApi(AdminOperatorRepository operators, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.operators = operators;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @PostMapping("/login")
    AdminSessionResponse login(@Valid @RequestBody AdminLoginRequest request) {
        var operator = operators.findByEmailIgnoreCase(request.email().trim())
            .filter(it -> it.active)
            .filter(it -> passwordEncoder.matches(request.password(), it.passwordHash))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "INVALID_ADMIN_CREDENTIALS"));

        var token = jwtService.issueAdmin(operator.id, operator.role.name());
        return new AdminSessionResponse(
            token.value(),
            token.expiresAt(),
            new AdminOperatorView(operator.id.toString(), operator.email, operator.displayName, operator.role.name())
        );
    }

    record AdminLoginRequest(@Email @NotBlank String email, @NotBlank String password) {}
    record AdminSessionResponse(String token, Instant expiresAt, AdminOperatorView operator) {}
    record AdminOperatorView(String id, String email, String displayName, String role) {}
}
