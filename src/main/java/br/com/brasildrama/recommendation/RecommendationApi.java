package br.com.brasildrama.recommendation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

record RecommendationItem(
    String dramaId,
    String title,
    String genre,
    String coverUrl,
    double score,
    String reason
) {}

record RecommendationResponse(
    String surface,
    String strategy,
    List<RecommendationItem> items
) {}

@RestController
class RecommendationApi {
    private final JdbcTemplate jdbc;

    RecommendationApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/recommendations/for-you")
    RecommendationResponse forYou(
        Authentication authentication,
        @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId,
        @RequestParam(defaultValue = "20") int limit
    ) {
        int normalizedLimit = Math.max(1, Math.min(50, limit));
        UUID userId = authenticatedUser(authentication);
        String visitor = normalizeVisitor(visitorId);
        Map<String, Double> affinity = loadGenreAffinity(userId, visitor);
        List<Candidate> candidates = loadCandidates();

        var ranked = new ArrayList<RecommendationItem>();
        for (Candidate candidate : candidates) {
            String normalizedGenre = candidate.genre() == null ? "" : candidate.genre().trim().toLowerCase(Locale.ROOT);
            double genreAffinity = affinity.getOrDefault(normalizedGenre, 0.0);
            double trend = Math.min(35.0, candidate.plays7d() * 1.2 + candidate.completions7d() * 2.4 + candidate.nextEpisodes7d() * 3.0);
            long ageDays = Math.max(0, ChronoUnit.DAYS.between(candidate.createdAt(), Instant.now()));
            double freshness = Math.max(0.0, 18.0 - Math.min(18.0, ageDays * 0.35));
            double affinityScore = Math.min(45.0, genreAffinity);
            double score = affinityScore + trend + freshness;
            String reason = genreAffinity > 0.0 ? "GENRE_AFFINITY" : candidate.plays7d() > 0 ? "TRENDING" : "FRESHNESS";
            ranked.add(new RecommendationItem(
                candidate.id().toString(), candidate.title(), candidate.genre(), candidate.coverUrl(), round(score), reason
            ));
        }

        ranked.sort(Comparator.comparingDouble(RecommendationItem::score).reversed().thenComparing(RecommendationItem::title, Comparator.nullsLast(String::compareToIgnoreCase)));
        if (ranked.size() > normalizedLimit) ranked = new ArrayList<>(ranked.subList(0, normalizedLimit));

        String strategy = affinity.isEmpty() ? "COLD_START_TRENDING_FRESHNESS" : "AFFINITY_TRENDING_FRESHNESS";
        return new RecommendationResponse("FOR_YOU", strategy, ranked);
    }

    private Map<String, Double> loadGenreAffinity(UUID userId, String visitorId) {
        if (userId == null && visitorId == null) return Map.of();

        String principalClause = userId != null ? "pe.user_id = ?" : "pe.visitor_id = ?";
        Object principal = userId != null ? userId : visitorId;
        var scores = new HashMap<String, Double>();
        RowCallbackHandler handler = rs -> {
            String genre = rs.getString("genre");
            if (genre != null && !genre.isBlank()) {
                scores.put(genre, Math.max(0.0, rs.getDouble("score")));
            }
        };
        jdbc.query("""
            select lower(d.genre) genre,
                   sum(case pe.event_type
                       when 'completion' then 9
                       when 'next_episode' then 8
                       when 'progress_75' then 6
                       when 'progress_50' then 4
                       when 'progress_25' then 2
                       when 'watch_3s' then 1
                       when 'abandon' then -3
                       when 'skip' then -5
                       else 0 end)::double precision score
            from playback_event pe
            join drama d on d.id = pe.drama_id
            where %s
              and pe.created_at >= now() - interval '60 days'
              and d.genre is not null
              and btrim(d.genre) <> ''
            group by lower(d.genre)
            """.formatted(principalClause), handler, principal);
        return scores;
    }

    private List<Candidate> loadCandidates() {
        return jdbc.query("""
            select d.id, d.title, d.genre, d.cover_url, d.created_at,
                   count(*) filter (where pe.event_type = 'play' and pe.created_at >= now() - interval '7 days') plays_7d,
                   count(*) filter (where pe.event_type = 'completion' and pe.created_at >= now() - interval '7 days') completions_7d,
                   count(*) filter (where pe.event_type = 'next_episode' and pe.created_at >= now() - interval '7 days') next_episodes_7d
            from drama d
            left join playback_event pe on pe.drama_id = d.id and pe.created_at >= now() - interval '7 days'
            where d.status = 'PUBLISHED'
            group by d.id, d.title, d.genre, d.cover_url, d.created_at
            """,
            (rs, row) -> {
                Timestamp createdAt = rs.getTimestamp("created_at");
                return new Candidate(
                    rs.getObject("id", UUID.class),
                    rs.getString("title"),
                    rs.getString("genre"),
                    rs.getString("cover_url"),
                    createdAt == null ? Instant.EPOCH : createdAt.toInstant(),
                    rs.getLong("plays_7d"),
                    rs.getLong("completions_7d"),
                    rs.getLong("next_episodes_7d")
                );
            }
        );
    }

    private static UUID authenticatedUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String normalizeVisitor(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (!normalized.matches("[A-Za-z0-9_-]{16,64}")) return null;
        return normalized;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private record Candidate(
        UUID id,
        String title,
        String genre,
        String coverUrl,
        Instant createdAt,
        long plays7d,
        long completions7d,
        long nextEpisodes7d
    ) {}
}
