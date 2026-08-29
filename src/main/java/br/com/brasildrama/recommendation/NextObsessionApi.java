package br.com.brasildrama.recommendation;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

record NextObsessionItem(
    String dramaId,
    String title,
    String genre,
    String coverUrl,
    double score,
    String reason
) {}

record NextObsessionResponse(
    String surface,
    String strategy,
    NextObsessionItem item
) {}

@RestController
class NextObsessionApi {
    private final JdbcTemplate jdbc;

    NextObsessionApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/recommendations/next-obsession")
    NextObsessionResponse nextObsession(
        Authentication authentication,
        @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId
    ) {
        UUID userId = authenticatedUser(authentication);
        String visitor = normalizeVisitor(visitorId);
        Map<String, Double> affinity = loadGenreAffinity(userId, visitor);
        Set<UUID> recentlyConsumed = loadRecentlyConsumed(userId, visitor);
        Set<UUID> suppressed = loadSuppressedDramaIds(userId, visitor);
        List<Candidate> candidates = loadCandidates();

        var ranked = new ArrayList<NextObsessionItem>();
        for (Candidate candidate : candidates) {
            if (suppressed.contains(candidate.id())) continue;
            String normalizedGenre = normalizeGenre(candidate.genre());
            double genreAffinity = affinity.getOrDefault(normalizedGenre, 0.0);
            double trend = Math.min(40.0, candidate.plays7d() * 1.1 + candidate.completions7d() * 2.8 + candidate.nextEpisodes7d() * 3.4);
            long ageDays = candidate.createdAt() == null ? 60 : Math.max(0, ChronoUnit.DAYS.between(candidate.createdAt(), Instant.now()));
            double freshness = Math.max(0.0, 20.0 - Math.min(20.0, ageDays * 0.30));
            double affinityScore = Math.min(50.0, genreAffinity);
            double discoveryBoost = recentlyConsumed.contains(candidate.id()) ? -60.0 : 12.0;
            double score = affinityScore + trend + freshness + discoveryBoost;
            String reason = genreAffinity > 0.0 ? "NEXT_GENRE_MATCH" : candidate.nextEpisodes7d() > 0 ? "BINGE_MOMENTUM" : candidate.plays7d() > 0 ? "RISING_NOW" : "FRESH_DISCOVERY";
            ranked.add(new NextObsessionItem(
                candidate.id().toString(), candidate.title(), candidate.genre(), candidate.coverUrl(), round(score), reason
            ));
        }

        ranked.sort(Comparator.comparingDouble(NextObsessionItem::score).reversed().thenComparing(NextObsessionItem::title, Comparator.nullsLast(String::compareToIgnoreCase)));
        NextObsessionItem item = ranked.isEmpty() ? null : ranked.getFirst();
        String strategy = affinity.isEmpty()
            ? "DISCOVERY_TRENDING_FRESHNESS_V1"
            : "AFFINITY_DISCOVERY_MOMENTUM_V1";
        return new NextObsessionResponse("NEXT_OBSESSION", strategy, item);
    }

    private Map<String, Double> loadGenreAffinity(UUID userId, String visitorId) {
        if (userId == null && visitorId == null) return Map.of();
        String principalClause = userId != null ? "pe.user_id = ?" : "pe.visitor_id = ?";
        Object principal = userId != null ? userId : visitorId;
        var scores = new HashMap<String, Double>();
        RowCallbackHandler handler = rs -> scores.put(normalizeGenre(rs.getString("genre")), Math.max(0.0, rs.getDouble("score")));
        jdbc.query("""
            select lower(coalesce(d.genre, '')) genre,
                   sum(case pe.event_type
                       when 'completion' then 10
                       when 'binge_session' then 10
                       when 'next_episode' then 8
                       when 'progress_75' then 6
                       when 'progress_50' then 4
                       when 'watch_3s' then 1
                       when 'abandon' then -3
                       when 'skip' then -5
                       when 'not_interested' then -35
                       else 0 end)::double precision score
            from playback_event pe
            join drama d on d.id = pe.drama_id
            where %s
              and pe.created_at >= now() - interval '60 days'
            group by lower(coalesce(d.genre, ''))
            """.formatted(principalClause), handler, principal);
        return scores;
    }

    private Set<UUID> loadRecentlyConsumed(UUID userId, String visitorId) {
        if (userId == null && visitorId == null) return Set.of();
        String principalClause = userId != null ? "pe.user_id = ?" : "pe.visitor_id = ?";
        Object principal = userId != null ? userId : visitorId;
        var ids = new HashSet<UUID>();
        jdbc.query("""
            select distinct pe.drama_id
            from playback_event pe
            where %s
              and pe.created_at >= now() - interval '21 days'
              and pe.event_type in ('play','watch_3s','progress_25','progress_50','progress_75','completion','next_episode')
            """.formatted(principalClause), (RowCallbackHandler) rs -> ids.add(rs.getObject("drama_id", UUID.class)), principal);
        return ids;
    }

    private Set<UUID> loadSuppressedDramaIds(UUID userId, String visitorId) {
        if (userId == null && visitorId == null) return Set.of();
        String principalClause = userId != null ? "pe.user_id = ?" : "pe.visitor_id = ?";
        Object principal = userId != null ? userId : visitorId;
        var ids = new HashSet<UUID>();
        jdbc.query("""
            select distinct pe.drama_id
            from playback_event pe
            where %s
              and pe.event_type = 'not_interested'
              and pe.created_at >= now() - interval '90 days'
            """.formatted(principalClause), (RowCallbackHandler) rs -> ids.add(rs.getObject("drama_id", UUID.class)), principal);
        return ids;
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
            (rs, row) -> new Candidate(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("genre"),
                rs.getString("cover_url"),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant(),
                rs.getLong("plays_7d"),
                rs.getLong("completions_7d"),
                rs.getLong("next_episodes_7d")
            )
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

    private static String normalizeGenre(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
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
