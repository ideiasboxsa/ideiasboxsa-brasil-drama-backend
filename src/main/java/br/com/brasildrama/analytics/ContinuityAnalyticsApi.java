package br.com.brasildrama.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

record ContinuityAnalyticsResponse(
    int days,
    long activeAccounts,
    long resumableSeries,
    double averageProgressPercent,
    long visitorIdentities,
    long nextEpisodeSignals
) {}

@RestController
class ContinuityAnalyticsApi {
    private final JdbcTemplate jdbc;

    ContinuityAnalyticsApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/admin/analytics/continuity")
    ContinuityAnalyticsResponse continuity(@RequestParam(defaultValue = "7") int days) {
        int normalizedDays = switch (days) {
            case 30 -> 30;
            case 90 -> 90;
            default -> 7;
        };
        String interval = normalizedDays + " days";

        var account = jdbc.queryForMap("""
            select count(distinct user_id) active_accounts,
                   count(*) resumable_series,
                   coalesce(avg(case
                       when duration_ms is not null and duration_ms > 0
                       then least(100.0, position_ms * 100.0 / duration_ms)
                       else null end), 0) average_progress
              from playback_history
             where updated_at >= now() - (?::interval)
               and (duration_ms is null or duration_ms = 0 or position_ms < duration_ms * 0.95)
            """, interval);

        Long visitors = jdbc.queryForObject("""
            select count(distinct visitor_id)
              from playback_event
             where visitor_id is not null
               and created_at >= now() - (?::interval)
               and event_type in ('play', 'pause', 'watch_3s', 'progress_25', 'progress_50', 'progress_75')
            """, Long.class, interval);

        Long nextEpisodes = jdbc.queryForObject("""
            select count(*)
              from playback_event
             where created_at >= now() - (?::interval)
               and event_type = 'next_episode'
            """, Long.class, interval);

        return new ContinuityAnalyticsResponse(
            normalizedDays,
            ((Number) account.get("active_accounts")).longValue(),
            ((Number) account.get("resumable_series")).longValue(),
            round(((Number) account.get("average_progress")).doubleValue()),
            visitors == null ? 0 : visitors,
            nextEpisodes == null ? 0 : nextEpisodes
        );
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
