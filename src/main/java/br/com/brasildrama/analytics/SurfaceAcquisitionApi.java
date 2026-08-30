package br.com.brasildrama.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

record SurfaceAcquisitionMetric(
    String surface,
    long impressions,
    long dramaOpens,
    long episodeOpens,
    long playIntents,
    int openRatePercent,
    int playRatePercent,
    double averagePosition
) {}

@RestController
class SurfaceAcquisitionApi {
    private final JdbcTemplate jdbc;

    SurfaceAcquisitionApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/admin/analytics/acquisition/surfaces")
    List<SurfaceAcquisitionMetric> bySurface(@RequestParam(defaultValue = "30") int days) {
        int period = normalizePeriod(days);
        return jdbc.query("""
            select surface,
                   count(*) filter (where event_type = 'impression') impressions,
                   count(*) filter (where event_type = 'drama_open') drama_opens,
                   count(*) filter (where event_type = 'episode_open') episode_opens,
                   count(*) filter (where event_type = 'play_intent') play_intents,
                   case
                     when count(*) filter (where event_type = 'impression') = 0 then 0
                     else round(
                       count(*) filter (where event_type = 'drama_open') * 100.0 /
                       count(*) filter (where event_type = 'impression')
                     )::int
                   end open_rate,
                   case
                     when count(*) filter (where event_type = 'drama_open') = 0 then 0
                     else round(
                       count(*) filter (where event_type = 'play_intent') * 100.0 /
                       count(*) filter (where event_type = 'drama_open')
                     )::int
                   end play_rate,
                   coalesce(round(avg(position_index)::numeric, 1), 0) average_position
            from content_event
            where created_at >= now() - (? * interval '1 day')
            group by surface
            order by play_intents desc, impressions desc, surface asc
            """,
            (rs, row) -> new SurfaceAcquisitionMetric(
                rs.getString("surface"),
                rs.getLong("impressions"),
                rs.getLong("drama_opens"),
                rs.getLong("episode_opens"),
                rs.getLong("play_intents"),
                rs.getInt("open_rate"),
                rs.getInt("play_rate"),
                rs.getDouble("average_position")
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
