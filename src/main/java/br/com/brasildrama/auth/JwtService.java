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
        var now = Instant.now();
        var expires = now.plus(ttl);
        var token = Jwts.builder()
            .subject(userId.toString())
            .issuedAt(Date.from(now))
            .expiration(Date.from(expires))
            .signWith(key)
            .compact();
        return new IssuedToken(token, expires);
    }

    public Optional<UUID> parseSubject(String token) {
        try {
            var subject = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload().getSubject();
            return Optional.of(UUID.fromString(subject));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    public record IssuedToken(String value, Instant expiresAt) {}
}
