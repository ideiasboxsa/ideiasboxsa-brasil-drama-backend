package br.com.brasildrama.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

record PlaybackEventRequest(
    String event,
    String sessionId,
    String dramaId,
    String episodeId,
    long positionMs,
    Long durationMs,
    String quality,
    String subtitle,
    String errorCode,
    String visitorId
) {}

@RestController
class PlaybackAnalyticsApi {
    private static final Set<String> ACCEPTED_EVENTS = Set.of(
        "play", "pause", "completion", "progress_25", "progress_50", "progress_75",
        "quality_changed", "subtitle_changed", "error"
    );

    private final JdbcTemplate jdbc;

    PlaybackAnalyticsApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/v1/analytics/playback/events")
    ResponseEntity<Void> ingest(@RequestBody PlaybackEventRequest request, Authentication authentication) {
        String event = required(request.event(), "event").toLowerCase(Locale.ROOT);
        if (!ACCEPTED_EVENTS.contains(event)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported playback event");
        }

        String sessionId = limited(required(request.sessionId(), "sessionId"), 120, "sessionId");
        UUID dramaId = uuid(request.dramaId(), "dramaId");
        UUID episodeId = uuid(request.episodeId(), "episodeId");
        if (request.positionMs() < 0 || request.durationMs() != null && request.durationMs() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid playback timing");
        }

        UUID userId = authenticatedUser(authentication);
        String visitorId = anonymousVisitor(request.visitorId());
        int inserted = jdbc.update("""
            insert into playback_event (
                id, user_id, visitor_id, session_id, drama_id, episode_id, event_type,
                position_ms, duration_ms, quality, subtitle, error_code
            )
            select ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
            from episode e
            where e.id = ? and e.drama_id = ?
            """,
            UUID.randomUUID(), userId, visitorId, sessionId, dramaId, episodeId, event,
            request.positionMs(), request.durationMs(),
            limitedNullable(request.quality(), 30, "quality"),
            limitedNullable(request.subtitle(), 60, "subtitle"),
            limitedNullable(request.errorCode(), 160, "errorCode"),
            episodeId, dramaId
        );
        if (inserted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Drama or episode not found");
        }
        return ResponseEntity.accepted().location(URI.create("/v1/analytics/playback/events")).build();
    }

    @DeleteMapping("/v1/analytics/playback/visitor")
    ResponseEntity<Void> resetVisitor(@RequestHeader("X-Visitor-Id") String visitorId) {
        String normalized = anonymousVisitor(visitorId);
        jdbc.update("update playback_event set visitor_id = null where visitor_id = ?", normalized);
        return ResponseEntity.noContent().build();
    }

    private static String anonymousVisitor(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (!normalized.matches("[A-Za-z0-9_-]{16,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid visitorId");
        }
        return normalized;
    }

    private static UUID authenticatedUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static UUID uuid(String value, String field) {
        try {
            return UUID.fromString(required(value, field));
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid " + field);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing " + field);
        }
        return value.trim();
    }

    private static String limited(String value, int max, String field) {
        if (value.length() > max) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is too long");
        }
        return value;
    }

    private static String limitedNullable(String value, int max, String field) {
        return value == null || value.isBlank() ? null : limited(value.trim(), max, field);
    }
}
