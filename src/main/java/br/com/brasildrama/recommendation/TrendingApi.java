package br.com.brasildrama.recommendation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

record TrendingItem(
    String dramaId,
    String title,
    String genre,
    String coverUrl,
    double score,
    long plays,
    long completions,
    long nextEpisodes
) {}

record TrendingResponse(
    String surface,
    String window,
    String strategy,
    List<TrendingItem> items
) {}

@RestController
class TrendingApi {
    private final JdbcTemplate jdbc;

    TrendingApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/recommendations/trending")
    TrendingResponse trending(
        @RequestParam(defaultValue = "24h") String window,
        @RequestParam(defaultValue = "10") int limit
    ) {
        String normalizedWindow = switch (window.toLowerCase()) {
            case "24h", "7d", "30d" -> window.toLowerCase();
            default -> "24h";
        };
        int normalizedLimit = Math.max(1, Math.min(50, limit));
        String interval = switch (normalizedWindow) {
            case "7d" -> "7 days";
            case "30d" -> "30 days";
            default -> "24 hours";
        };

        List<TrendingItem> items = jdbc.query("""
            select d.id, d.title, d.genre, d.cover_url,
                   count(*) filter (where pe.event_type = 'play') plays,
                   count(*) filter (where pe.event_type = 'completion') completions,
                   count(*) filter (where pe.event_type = 'next_episode') next_episodes,
                   (
                     count(*) filter (where pe.event_type = 'play') * 1.0 +
                     count(*) filter (where pe.event_type = 'watch_3s') * 0.5 +
                     count(*) filter (where pe.event_type = 'progress_75') * 1.5 +
                     count(*) filter (where pe.event_type = 'completion') * 3.0 +
                     count(*) filter (where pe.event_type = 'next_episode') * 4.0 -
                     count(*) filter (where pe.event_type = 'skip') * 1.5 -
                     count(*) filter (where pe.event_type = 'abandon') * 1.0
                   )::double precision trend_score
            from drama d
            left join playback_event pe
              on pe.drama_id = d.id
             and pe.created_at >= now() - (?::interval)
            where d.status = 'PUBLISHED'
            group by d.id, d.title, d.genre, d.cover_url
            order by trend_score desc, plays desc, d.title asc
            limit ?
            """,
            (rs, row) -> new TrendingItem(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("title"),
                rs.getString("genre"),
                rs.getString("cover_url"),
                round(rs.getDouble("trend_score")),
                rs.getLong("plays"),
                rs.getLong("completions"),
                rs.getLong("next_episodes")
            ),
            interval,
            normalizedLimit
        );

        return new TrendingResponse("TRENDING", normalizedWindow, "VELOCITY_COMPLETION_CONTINUITY_V1", items);
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
