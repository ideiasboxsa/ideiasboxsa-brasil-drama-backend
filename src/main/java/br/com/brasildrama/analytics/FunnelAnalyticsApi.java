package br.com.brasildrama.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

record EngagementFunnelMetrics(
    int days,
    long started,
    long watched3s,
    long reached25,
    long reached50,
    long reached75,
    long completed,
    long nextEpisodes,
    long skips,
    long abandons,
    long errors,
    int hookRatePercent,
    int reached25RatePercent,
    int reached50RatePercent,
    int reached75RatePercent,
    int completionRatePercent,
    int nextEpisodeRatePercent,
    int skipRatePercent,
    int abandonRatePercent,
    int errorRatePercent
) {}

@RestController
class FunnelAnalyticsApi {
    private final JdbcTemplate jdbc;

    FunnelAnalyticsApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/admin/analytics/funnel")
    EngagementFunnelMetrics funnel(@RequestParam(defaultValue = "30") int days) {
        int period = normalizePeriod(days);
        return jdbc.queryForObject("""
            with sessions as (
                select session_id, episode_id,
                       bool_or(event_type = 'play') started,
                       bool_or(event_type = 'watch_3s') watched_3s,
                       bool_or(event_type = 'progress_25') reached_25,
                       bool_or(event_type = 'progress_50') reached_50,
                       bool_or(event_type = 'progress_75') reached_75,
                       bool_or(event_type = 'completion') completed,
                       bool_or(event_type = 'next_episode') next_episode,
                       bool_or(event_type = 'skip') skipped,
                       bool_or(event_type = 'abandon') abandoned,
                       bool_or(event_type = 'error') errored
                  from playback_event
                 where created_at >= now() - (? * interval '1 day')
                 group by session_id, episode_id
            ), totals as (
                select count(*) filter (where started) started,
                       count(*) filter (where watched_3s) watched_3s,
                       count(*) filter (where reached_25) reached_25,
                       count(*) filter (where reached_50) reached_50,
                       count(*) filter (where reached_75) reached_75,
                       count(*) filter (where completed) completed,
                       count(*) filter (where next_episode) next_episodes,
                       count(*) filter (where skipped) skips,
                       count(*) filter (where abandoned) abandons,
                       count(*) filter (where errored) errors
                  from sessions
            )
            select started, watched_3s, reached_25, reached_50, reached_75, completed,
                   next_episodes, skips, abandons, errors,
                   case when started = 0 then 0 else round(watched_3s * 100.0 / started)::int end hook_rate,
                   case when started = 0 then 0 else round(reached_25 * 100.0 / started)::int end reached_25_rate,
                   case when started = 0 then 0 else round(reached_50 * 100.0 / started)::int end reached_50_rate,
                   case when started = 0 then 0 else round(reached_75 * 100.0 / started)::int end reached_75_rate,
                   case when started = 0 then 0 else round(completed * 100.0 / started)::int end completion_rate,
                   case when completed = 0 then 0 else round(next_episodes * 100.0 / completed)::int end next_episode_rate,
                   case when started = 0 then 0 else round(skips * 100.0 / started)::int end skip_rate,
                   case when started = 0 then 0 else round(abandons * 100.0 / started)::int end abandon_rate,
                   case when started = 0 then 0 else round(errors * 100.0 / started)::int end error_rate
              from totals
            """,
            (rs, row) -> new EngagementFunnelMetrics(
                period,
                rs.getLong("started"),
                rs.getLong("watched_3s"),
                rs.getLong("reached_25"),
                rs.getLong("reached_50"),
                rs.getLong("reached_75"),
                rs.getLong("completed"),
                rs.getLong("next_episodes"),
                rs.getLong("skips"),
                rs.getLong("abandons"),
                rs.getLong("errors"),
                rs.getInt("hook_rate"),
                rs.getInt("reached_25_rate"),
                rs.getInt("reached_50_rate"),
                rs.getInt("reached_75_rate"),
                rs.getInt("completion_rate"),
                rs.getInt("next_episode_rate"),
                rs.getInt("skip_rate"),
                rs.getInt("abandon_rate"),
                rs.getInt("error_rate")
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
