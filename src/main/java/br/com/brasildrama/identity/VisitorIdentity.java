package br.com.brasildrama.identity;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Server contract for guest-first identity.
 * Analytics identifiers are intentionally not accepted as economic identity.
 */
public record VisitorIdentity(UUID id) {
    public static final String HEADER = "X-Visitor-ID";

    public static VisitorIdentity parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, HEADER + " is required");
        }
        try {
            return new VisitorIdentity(UUID.fromString(raw.trim()));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, HEADER + " must be a UUID");
        }
    }

    public String externalId() {
        return id.toString();
    }
}
