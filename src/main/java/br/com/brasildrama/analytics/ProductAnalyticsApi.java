package br.com.brasildrama.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

record ProductSignalMetrics(
    long started,
    long watched3s,
    long reached25,
    long completed,
    long skips,
    long abandons,
    long nextEpisodes,
    long bingeSessions,
    int hookRatePercent,
    int completionRatePercent,
    int nextEpisodeRatePercent
) {}

@RestController
class ProductAnalyticsApi {
    private final JdbcTemplate jdbc;

    ProductAnalyticsApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/admin/analytics/product-signals")
    ProductSignalMetrics productSignals(@RequestParam(defaultValue = "30") int days) {
        int period = normalizePeriod(days);
        return jdbc.queryForObject("""
            with sessions as (
                select session_id, episode_id,
                       bool_or(event_type = 'play') started,
                       bool_or(event_type = 'watch_3s') watched_3s,
                       bool_or(event_type = 'progress_25') reached_25,
                       bool_or(event_type = 'completion') completed,
                       bool_or(event_type = 'skip') skipped,
                       bool_or(event_type = 'abandon') abandoned,
                       bool_or(event_type = 'next_episode') next_episode,
                       bool_or(event_type = 'binge_session') binge_session
                from playback_event
                where created_at >= now() - (? * interval '1 day')
                group by session_id, episode_id
            ), totals as (
                select count(*) filter (where started) started,
                       count(*) filter (where watched_3s) watched_3s,
                       count(*) filter (where reached_25) reached_25,
                       count(*) filter (where completed) completed,
                       count(*) filter (where skipped) skips,
                       count(*) filter (where abandoned) abandons,
                       count(*) filter (where next_episode) next_episodes,
                       count(*) filter (where binge_session) binge_sessions
                from sessions
            )
            select started, watched_3s, reached_25, completed, skips, abandons, next_episodes, binge_sessions,
                   case when started = 0 then 0 else round(watched_3s * 100.0 / started)::int end hook_rate,
                   case when started = 0 then 0 else round(completed * 100.0 / started)::int end completion_rate,
                   case when completed = 0 then 0 else round(next_episodes * 100.0 / completed)::int end next_episode_rate
            from totals
            """,
            (rs, row) -> new ProductSignalMetrics(
                rs.getLong("started"),
                rs.getLong("watched_3s"),
                rs.getLong("reached_25"),
                rs.getLong("completed"),
                rs.getLong("skips"),
                rs.getLong("abandons"),
                rs.getLong("next_episodes"),
                rs.getLong("binge_sessions"),
                rs.getInt("hook_rate"),
                rs.getInt("completion_rate"),
                rs.getInt("next_episode_rate")
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
}
