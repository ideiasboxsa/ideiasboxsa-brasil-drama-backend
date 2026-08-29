package br.com.brasildrama.recommendation;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
class RecommendationFeedbackApi {
    private final JdbcTemplate jdbc;

    RecommendationFeedbackApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/v1/recommendations/not-interested/{dramaId}")
    ResponseEntity<Void> notInterested(
        @PathVariable String dramaId,
        Authentication authentication,
        @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId
    ) {
        UUID drama = parseUuid(dramaId);
        UUID userId = authenticatedUser(authentication);
        String visitor = normalizeVisitor(visitorId);
        if (userId == null && visitor == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing recommendation identity");
        }

        int inserted = jdbc.update("""
            insert into playback_event (
                id, user_id, visitor_id, session_id, drama_id, episode_id, event_type,
                position_ms, duration_ms, quality, subtitle, error_code
            )
            select ?, ?, ?, 'recommendation-feedback', d.id, e.id, 'not_interested',
                   0, null, null, null, null
            from drama d
            join lateral (
                select id from episode where drama_id = d.id order by number asc, id asc limit 1
            ) e on true
            where d.id = ? and d.status = 'PUBLISHED'
            """, UUID.randomUUID(), userId, visitor, drama);

        if (inserted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Drama not found or has no episodes");
        }
        return ResponseEntity.noContent().build();
    }

    private static UUID authenticatedUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalizeVisitor(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (!normalized.matches("[A-Za-z0-9_-]{16,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid visitor id");
        }
        return normalized;
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid dramaId");
        }
    }
}
