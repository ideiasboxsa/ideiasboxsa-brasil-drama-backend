package br.com.brasildrama.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
class BingeAnalyticsApi {
    private final JdbcTemplate jdbc;

    BingeAnalyticsApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/admin/analytics/binge")
    BingeAnalyticsResponse binge(@RequestParam(defaultValue = "30") int days) {
        int normalizedDays = normalizeDays(days);
        Map<String, Object> row = jdbc.queryForMap("""
            select
                count(*) filter (where event_type = 'next_episode') as next_episode_events,
                count(*) filter (where event_type = 'binge_session') as binge_session_events,
                count(distinct session_id) filter (where event_type = 'next_episode') as continuing_sessions,
                count(distinct session_id) filter (where event_type = 'binge_session') as binge_sessions,
                count(distinct coalesce(user_id::text, visitor_id)) filter (where event_type = 'next_episode') as viewers_continuing
            from playback_event
            where created_at >= now() - (? * interval '1 day')
              and event_type in ('next_episode', 'binge_session')
            """, normalizedDays);

        long nextEpisodeEvents = number(row.get("next_episode_events"));
        long bingeSessionEvents = number(row.get("binge_session_events"));
        long continuingSessions = number(row.get("continuing_sessions"));
        long bingeSessions = number(row.get("binge_sessions"));
        long viewersContinuing = number(row.get("viewers_continuing"));
        double bingeConversionPercent = continuingSessions == 0
            ? 0d
            : Math.round((bingeSessions * 10000d) / continuingSessions) / 100d;

        return new BingeAnalyticsResponse(
            normalizedDays,
            nextEpisodeEvents,
            bingeSessionEvents,
            continuingSessions,
            bingeSessions,
            viewersContinuing,
            bingeConversionPercent
        );
    }

    private static int normalizeDays(int days) {
        return switch (days) {
            case 7, 30, 90 -> days;
            default -> 30;
        };
    }

    private static long number(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}

record BingeAnalyticsResponse(
    int days,
    long nextEpisodeEvents,
    long bingeSessionEvents,
    long continuingSessions,
    long bingeSessions,
    long viewersContinuing,
    double bingeConversionPercent
) {}
