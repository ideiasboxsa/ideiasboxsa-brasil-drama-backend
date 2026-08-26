package br.com.brasildrama.admin;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

@Service
class AdminPasswordResetService {
    private final AdminOperatorRepository operators;
    private final AdminPasswordResetTokenRepository tokens;
    private final MailgunAdminPasswordResetMailer mailer;
    private final PasswordEncoder passwordEncoder;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Duration ttl;
    private final String resetPageUrl;

    AdminPasswordResetService(
        AdminOperatorRepository operators,
        AdminPasswordResetTokenRepository tokens,
        MailgunAdminPasswordResetMailer mailer,
        PasswordEncoder passwordEncoder,
        @Value("${admin.password-reset.ttl:PT30M}") Duration ttl,
        @Value("${admin.password-reset.page-url:https://studio-drama-dev.ideiasbox.com/forgot-password}") String resetPageUrl
    ) {
        this.operators = operators;
        this.tokens = tokens;
        this.mailer = mailer;
        this.passwordEncoder = passwordEncoder;
        this.ttl = ttl;
        this.resetPageUrl = resetPageUrl;
    }

    @Transactional
    public void request(String rawEmail) {
        var email = rawEmail == null ? "" : rawEmail.trim().toLowerCase();
        operators.findByEmailIgnoreCase(email)
            .filter(operator -> operator.active)
            .ifPresent(this::issue);
    }

    private void issue(AdminOperator operator) {
        var raw = new byte[32];
        secureRandom.nextBytes(raw);
        var token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        var link = resetPageUrl + "?token=" + URLEncoder.encode(token, StandardCharsets.UTF_8);

        if (!mailer.send(operator.email, link)) return;

        var now = Instant.now();
        tokens.findAllByOperatorIdAndUsedAtIsNull(operator.id)
            .forEach(previous -> previous.usedAt = now);

        var reset = new AdminPasswordResetToken();
        reset.operatorId = operator.id;
        reset.tokenHash = hash(token);
        reset.expiresAt = now.plus(ttl);
        tokens.save(reset);
    }

    @Transactional
    public void confirm(String rawToken, String newPassword) {
        if (rawToken == null || rawToken.isBlank() || newPassword == null || newPassword.length() < 12 || newPassword.length() > 128) {
            throw invalid();
        }

        var now = Instant.now();
        var reset = tokens.findFirstByTokenHashAndUsedAtIsNullAndExpiresAtAfter(hash(rawToken.trim()), now)
            .orElseThrow(AdminPasswordResetService::invalid);
        var operator = operators.findById(reset.operatorId).filter(it -> it.active)
            .orElseThrow(AdminPasswordResetService::invalid);

        operator.passwordHash = passwordEncoder.encode(newPassword);
        reset.usedAt = now;
        operators.save(operator);
        tokens.save(reset);
    }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_OR_EXPIRED_RESET_TOKEN");
    }
}
