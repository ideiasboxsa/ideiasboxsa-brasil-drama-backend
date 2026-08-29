package br.com.brasildrama.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

record RejectedGenre(String genre, long rejections) {}

record RecommendationFeedbackAnalytics(
    int days,
    long rejections,
    long uniqueActors,
    long rejectedDramas,
    List<RejectedGenre> topGenres
) {}

@RestController
class RecommendationFeedbackAnalyticsApi {
    private final JdbcTemplate jdbc;

    RecommendationFeedbackAnalyticsApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/admin/analytics/recommendation-feedback")
    RecommendationFeedbackAnalytics metrics(@RequestParam(defaultValue = "7") int days) {
        int normalizedDays = days == 30 || days == 90 ? days : 7;
        long rejections = count("""
            select count(*) from playback_event
            where event_type = 'not_interested'
              and created_at >= now() - (? * interval '1 day')
            """, normalizedDays);
        long uniqueActors = count("""
            select count(distinct coalesce(user_id::text, visitor_id)) from playback_event
            where event_type = 'not_interested'
              and created_at >= now() - (? * interval '1 day')
            """, normalizedDays);
        long rejectedDramas = count("""
            select count(distinct drama_id) from playback_event
            where event_type = 'not_interested'
              and created_at >= now() - (? * interval '1 day')
            """, normalizedDays);
        List<RejectedGenre> topGenres = jdbc.query("""
            select coalesce(nullif(btrim(d.genre), ''), 'Sem gênero') genre, count(*) rejections
            from playback_event pe
            join drama d on d.id = pe.drama_id
            where pe.event_type = 'not_interested'
              and pe.created_at >= now() - (? * interval '1 day')
            group by coalesce(nullif(btrim(d.genre), ''), 'Sem gênero')
            order by rejections desc, genre asc
            limit 8
            """, (rs, row) -> new RejectedGenre(rs.getString("genre"), rs.getLong("rejections")), normalizedDays);
        return new RecommendationFeedbackAnalytics(normalizedDays, rejections, uniqueActors, rejectedDramas, topGenres);
    }

    private long count(String sql, int days) {
        Long value = jdbc.queryForObject(sql, Long.class, days);
        return value == null ? 0L : value;
    }
}
