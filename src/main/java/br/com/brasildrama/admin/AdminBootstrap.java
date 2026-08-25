package br.com.brasildrama.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class AdminBootstrap implements ApplicationRunner {
    private final AdminOperatorRepository operators;
    private final PasswordEncoder passwordEncoder;
    private final boolean enabled;
    private final String email;
    private final String displayName;
    private final String password;

    AdminBootstrap(
        AdminOperatorRepository operators,
        PasswordEncoder passwordEncoder,
        @Value("${admin.bootstrap.enabled:${ADMIN_BOOTSTRAP_ENABLED:false}}") boolean enabled,
        @Value("${admin.bootstrap.email:${ADMIN_BOOTSTRAP_EMAIL:}}") String email,
        @Value("${admin.bootstrap.display-name:${ADMIN_BOOTSTRAP_DISPLAY_NAME:Super Admin}}") String displayName,
        @Value("${admin.bootstrap.password:${ADMIN_BOOTSTRAP_PASSWORD:}}") String password
    ) {
        this.operators = operators;
        this.passwordEncoder = passwordEncoder;
        this.enabled = enabled;
        this.email = email == null ? "" : email.trim().toLowerCase();
        this.displayName = displayName == null ? "Super Admin" : displayName.trim();
        this.password = password == null ? "" : password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        if (email.isBlank()) throw new IllegalStateException("ADMIN_BOOTSTRAP_EMAIL_REQUIRED");
        if (password.length() < 12) throw new IllegalStateException("ADMIN_BOOTSTRAP_PASSWORD_TOO_SHORT");

        if (operators.findByEmailIgnoreCase(email).isPresent()) return;

        var operator = new AdminOperator();
        operator.email = email;
        operator.displayName = displayName.isBlank() ? "Super Admin" : displayName;
        operator.passwordHash = passwordEncoder.encode(password);
        operator.role = AdminRole.SUPER_ADMIN;
        operator.active = true;
        operators.save(operator);
    }
}
