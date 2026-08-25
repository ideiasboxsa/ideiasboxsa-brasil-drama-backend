package br.com.brasildrama.auth;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Service
public class JwtService {
    private final SecretKey key;
    private final Duration ttl;

    public JwtService(
        @Value("${security.jwt.secret:${JWT_SECRET:dev-only-change-this-secret-32-bytes}}") String secret,
        @Value("${security.jwt.ttl:${JWT_TTL:PT24H}}") Duration ttl
    ) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) throw new IllegalStateException("JWT_SECRET_TOO_SHORT");
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.ttl = ttl;
    }

    public IssuedToken issue(UUID userId) {
        return issue(userId, "USER", null);
    }

    public IssuedToken issueAdmin(UUID operatorId, String role) {
        return issue(operatorId, "ADMIN", role);
    }

    private IssuedToken issue(UUID subjectId, String tokenType, String role) {
        var now = Instant.now();
        var expires = now.plus(ttl);
        var builder = Jwts.builder()
            .subject(subjectId.toString())
            .claim("token_type", tokenType)
            .issuedAt(Date.from(now))
            .expiration(Date.from(expires));
        if (role != null) builder.claim("role", role);
        var token = builder.signWith(key).compact();
        return new IssuedToken(token, expires);
    }

    public Optional<UUID> parseSubject(String token) {
        return parse(token).map(ParsedToken::subjectId);
    }

    public Optional<ParsedToken> parse(String token) {
        try {
            var payload = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload();
            var subject = UUID.fromString(payload.getSubject());
            var tokenType = payload.get("token_type", String.class);
            if (tokenType == null) tokenType = "USER";
            var role = payload.get("role", String.class);
            return Optional.of(new ParsedToken(subject, tokenType, role));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public record IssuedToken(String value, Instant expiresAt) {}
    public record ParsedToken(UUID subjectId, String tokenType, String role) {}
}
