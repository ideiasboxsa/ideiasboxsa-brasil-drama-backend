package br.com.brasildrama.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

record AnalyticsSummary(
    long plays,
    long viewers,
    long completedEpisodes,
    int averageCompletionPercent,
    long playbackErrors
) {}

record AnalyticsRetention(
    long started,
    long reached25,
    long reached50,
    long reached75,
    long completed
) {}

record AnalyticsError(
    String code,
    long occurrences,
    long affectedSessions
) {}

record AnalyticsBreakdown(String value, long sessions) {}

record AnalyticsExperience(
    long totalSessions,
    long sessionsWithSubtitles,
    List<AnalyticsBreakdown> qualities,
    List<AnalyticsBreakdown> subtitles
) {}

record DramaAnalytics(
    UUID dramaId,
    String title,
    long plays,
    long viewers,
    long completedEpisodes,
    int averageCompletionPercent,
    long playbackErrors
) {}

record EpisodeAnalytics(
    UUID episodeId,
    UUID dramaId,
    String dramaTitle,
    int episodeNumber,
    String title,
    long plays,
    long completed,
    int averageCompletionPercent
) {}

record AdminCatalogAnalytics(
    int days,
    AnalyticsSummary summary,
    AnalyticsRetention retention,
    AnalyticsExperience experience,
    List<AnalyticsError> errors,
    List<DramaAnalytics> dramas,
    List<EpisodeAnalytics> episodes
) {}

@RestController
class AdminAnalyticsApi {
    private final JdbcTemplate jdbc;

    AdminAnalyticsApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/admin/analytics/catalog")
    AdminCatalogAnalytics catalog(@RequestParam(defaultValue = "30") int days) {
        int period = normalizePeriod(days);
        return new AdminCatalogAnalytics(
            period,
            summary(period),
            retention(period),
            experience(period),
            errors(period),
            dramas(period),
            episodes(period)
        );
    }

    private AnalyticsSummary summary(int days) {
        return jdbc.queryForObject("""
            with sessions as (
                select session_id, episode_id,
                       (array_agg(user_id) filter (where user_id is not null))[1] user_id,
                       bool_or(event_type = 'play') played,
                       bool_or(event_type = 'completion') completed,
                       max(position_ms) position_ms,
                       max(duration_ms) duration_ms,
                       count(*) filter (where event_type = 'error') errors
                from playback_event
                where created_at >= now() - (? * interval '1 day')
                group by session_id, episode_id
            )
            select count(*) filter (where played),
                   count(distinct coalesce(user_id::text, session_id)),
                   count(*) filter (where completed),
                   coalesce(round(avg(least(100.0, greatest(0.0, position_ms * 100.0 / nullif(duration_ms, 0))))), 0)::int,
                   coalesce(sum(errors), 0)
            from sessions
            """,
            (rs, row) -> new AnalyticsSummary(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getLong(5)
            ),
            days
        );
    }

    private AnalyticsRetention retention(int days) {
        return jdbc.queryForObject("""
            with sessions as (
                select session_id, episode_id,
                       bool_or(event_type = 'play') started,
                       bool_or(event_type = 'progress_25') reached_25,
                       bool_or(event_type = 'progress_50') reached_50,
                       bool_or(event_type = 'progress_75') reached_75,
                       bool_or(event_type = 'completion') completed
                from playback_event
                where created_at >= now() - (? * interval '1 day')
                group by session_id, episode_id
            )
            select count(*) filter (where started),
                   count(*) filter (where reached_25),
                   count(*) filter (where reached_50),
                   count(*) filter (where reached_75),
                   count(*) filter (where completed)
            from sessions
            """,
            (rs, row) -> new AnalyticsRetention(
                rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)
            ),
            days
        );
    }

    private AnalyticsExperience experience(int days) {
        long totalSessions = count("""
            select count(*) from (
                select distinct session_id, episode_id
                from playback_event
                where created_at >= now() - (? * interval '1 day')
            ) sessions
            """, days);
        long sessionsWithSubtitles = count("""
            select count(*) from (
                select distinct on (session_id, episode_id) subtitle
                from playback_event
                where created_at >= now() - (? * interval '1 day')
                order by session_id, episode_id, created_at desc
            ) latest
            where coalesce(subtitle, 'OFF') not in ('OFF', 'AUTO')
            """, days);

        return new AnalyticsExperience(
            totalSessions,
            sessionsWithSubtitles,
            breakdown("quality", days),
            breakdown("subtitle", days)
        );
    }

    private List<AnalyticsBreakdown> breakdown(String column, int days) {
        if (!column.equals("quality") && !column.equals("subtitle")) {
            throw new IllegalArgumentException("Unsupported analytics dimension");
        }
        String sql = """
            select coalesce(nullif(%s, ''), 'UNKNOWN') value, count(*) sessions
            from (
                select distinct on (session_id, episode_id) %s
                from playback_event
                where created_at >= now() - (? * interval '1 day')
                order by session_id, episode_id, created_at desc
            ) latest
            group by coalesce(nullif(%s, ''), 'UNKNOWN')
            order by sessions desc, value
            """.formatted(column, column, column);
        return jdbc.query(
            sql,
            (rs, row) -> new AnalyticsBreakdown(rs.getString("value"), rs.getLong("sessions")),
            days
        );
    }

    private long count(String sql, int days) {
        Long value = jdbc.queryForObject(sql, Long.class, days);
        return value == null ? 0 : value;
    }

    private List<AnalyticsError> errors(int days) {
        return jdbc.query("""
            select coalesce(nullif(error_code, ''), 'PLAYBACK_UNKNOWN') code,
                   count(*) occurrences,
                   count(distinct session_id) affected_sessions
            from playback_event
            where event_type = 'error'
              and created_at >= now() - (? * interval '1 day')
            group by coalesce(nullif(error_code, ''), 'PLAYBACK_UNKNOWN')
            order by occurrences desc, code
            limit 10
            """,
            (rs, row) -> new AnalyticsError(
                rs.getString("code"),
                rs.getLong("occurrences"),
                rs.getLong("affected_sessions")
            ),
            days
        );
    }

    private List<DramaAnalytics> dramas(int days) {
        return jdbc.query("""
            with sessions as (
                select drama_id, episode_id, session_id,
                       (array_agg(user_id) filter (where user_id is not null))[1] user_id,
                       bool_or(event_type = 'play') played,
                       bool_or(event_type = 'completion') completed,
                       max(position_ms) position_ms,
                       max(duration_ms) duration_ms,
                       count(*) filter (where event_type = 'error') errors
                from playback_event
                where created_at >= now() - (? * interval '1 day')
                group by drama_id, episode_id, session_id
            )
            select d.id, d.title,
                   count(*) filter (where s.played) plays,
                   count(distinct coalesce(s.user_id::text, s.session_id)) viewers,
                   count(*) filter (where s.completed) completed,
                   coalesce(round(avg(least(100.0, greatest(0.0, s.position_ms * 100.0 / nullif(s.duration_ms, 0))))), 0)::int completion,
                   coalesce(sum(s.errors), 0) errors
            from sessions s
            join drama d on d.id = s.drama_id
            group by d.id, d.title
            order by plays desc, d.title
            limit 50
            """,
            (rs, row) -> new DramaAnalytics(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getLong("plays"),
                rs.getLong("viewers"),
                rs.getLong("completed"),
                rs.getInt("completion"),
                rs.getLong("errors")
            ),
            days
        );
    }

    private List<EpisodeAnalytics> episodes(int days) {
        return jdbc.query("""
            with sessions as (
                select drama_id, episode_id, session_id,
                       bool_or(event_type = 'play') played,
                       bool_or(event_type = 'completion') completed,
                       max(position_ms) position_ms,
                       max(duration_ms) duration_ms
                from playback_event
                where created_at >= now() - (? * interval '1 day')
                group by drama_id, episode_id, session_id
            )
            select e.id, e.drama_id, d.title drama_title, e.number episode_number, e.title,
                   count(*) filter (where s.played) plays,
                   count(*) filter (where s.completed) completed,
                   coalesce(round(avg(least(100.0, greatest(0.0, s.position_ms * 100.0 / nullif(s.duration_ms, 0))))), 0)::int completion
            from sessions s
            join episode e on e.id = s.episode_id
            join drama d on d.id = e.drama_id
            group by e.id, e.drama_id, d.title, e.number, e.title
            order by plays desc, d.title, e.number
            limit 100
            """,
            (rs, row) -> new EpisodeAnalytics(
                rs.getObject("id", UUID.class),
                rs.getObject("drama_id", UUID.class),
                rs.getString("drama_title"),
                rs.getInt("episode_number"),
                rs.getString("title"),
                rs.getLong("plays"),
                rs.getLong("completed"),
                rs.getInt("completion")
            ),
            days
        );
    }

    private static int normalizePeriod(int days) {
        return switch (days) {
            case 7, 30, 90 -> days;
            default -> 30;
        };
    }
}
