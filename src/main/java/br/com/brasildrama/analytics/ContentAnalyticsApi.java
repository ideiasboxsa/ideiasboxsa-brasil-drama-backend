package br.com.brasildrama.analytics;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

record ContentEventRequest(
    String event,
    String sessionId,
    String surface,
    String dramaId,
    String episodeId,
    Integer positionIndex,
    String visitorId
) {}

record AcquisitionMetrics(
    long impressions,
    long dramaOpens,
    long episodeOpens,
    long playIntents,
    int dramaOpenRatePercent,
    int playIntentRatePercent
) {}

@RestController
class ContentAnalyticsApi {
    private static final Set<String> ACCEPTED_EVENTS = Set.of(
        "impression", "drama_open", "episode_open", "play_intent"
    );

    private final JdbcTemplate jdbc;

    ContentAnalyticsApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/v1/analytics/content/events")
    ResponseEntity<Void> ingest(@RequestBody ContentEventRequest request, Authentication authentication) {
        String event = required(request.event(), "event").toLowerCase(Locale.ROOT);
        if (!ACCEPTED_EVENTS.contains(event)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported content event");
        }

        String sessionId = limited(required(request.sessionId(), "sessionId"), 120, "sessionId");
        String surface = normalizeSurface(request.surface());
        UUID dramaId = uuid(request.dramaId(), "dramaId");
        UUID episodeId = nullableUuid(request.episodeId(), "episodeId");
        if ((event.equals("episode_open") || event.equals("play_intent")) && episodeId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "episodeId is required for " + event);
        }
        if (request.positionIndex() != null && request.positionIndex() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid positionIndex");
        }

        UUID userId = authenticatedUser(authentication);
        String visitorId = anonymousVisitor(request.visitorId());

        int inserted;
        if (episodeId == null) {
            inserted = jdbc.update("""
                insert into content_event (
                    id, user_id, visitor_id, session_id, event_type, surface,
                    drama_id, episode_id, position_index
                )
                select ?, ?, ?, ?, ?, ?, d.id, null, ?
                from drama d
                where d.id = ?
                """,
                UUID.randomUUID(), userId, visitorId, sessionId, event, surface,
                request.positionIndex(), dramaId
            );
        } else {
            inserted = jdbc.update("""
                insert into content_event (
                    id, user_id, visitor_id, session_id, event_type, surface,
                    drama_id, episode_id, position_index
                )
                select ?, ?, ?, ?, ?, ?, d.id, e.id, ?
                from drama d
                join episode e on e.drama_id = d.id
                where d.id = ? and e.id = ?
                """,
                UUID.randomUUID(), userId, visitorId, sessionId, event, surface,
                request.positionIndex(), dramaId, episodeId
            );
        }
        if (inserted == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Drama or episode not found");
        }
        return ResponseEntity.accepted().location(URI.create("/v1/analytics/content/events")).build();
    }

    @GetMapping("/v1/admin/analytics/acquisition")
    AcquisitionMetrics acquisition(@RequestParam(defaultValue = "30") int days) {
        int period = normalizePeriod(days);
        return jdbc.queryForObject("""
            with totals as (
                select count(*) filter (where event_type = 'impression') impressions,
                       count(*) filter (where event_type = 'drama_open') drama_opens,
                       count(*) filter (where event_type = 'episode_open') episode_opens,
                       count(*) filter (where event_type = 'play_intent') play_intents
                from content_event
                where created_at >= now() - (? * interval '1 day')
            )
            select impressions, drama_opens, episode_opens, play_intents,
                   case when impressions = 0 then 0 else round(drama_opens * 100.0 / impressions)::int end drama_open_rate,
                   case when drama_opens = 0 then 0 else round(play_intents * 100.0 / drama_opens)::int end play_intent_rate
            from totals
            """,
            (rs, row) -> new AcquisitionMetrics(
                rs.getLong("impressions"),
                rs.getLong("drama_opens"),
                rs.getLong("episode_opens"),
                rs.getLong("play_intents"),
                rs.getInt("drama_open_rate"),
                rs.getInt("play_intent_rate")
            ),
            period
        );
    }

    private static int normalizePeriod(int days) {
        return switch (days) {
            case 7, 30, 90 -> days;
            default -> 30;
        };
    }

    private static String normalizeSurface(String value) {
        String normalized = limited(required(value, "surface"), 40, "surface")
            .trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_-]{1,40}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid surface");
        }
        return normalized;
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

    private static UUID nullableUuid(String value, String field) {
        if (value == null || value.isBlank()) return null;
        return uuid(value, field);
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
}
