package br.com.brasildrama.home;

import br.com.brasildrama.catalog.CatalogQueryService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

record HomeItemDto(String dramaId, String episodeId, Long progressMs, String badge, String subtitle, String imageUrl) {}
record HomeSectionDto(String type, String title, List<HomeItemDto> items) {}
record HomeResponseDto(String heroDramaId, List<HomeSectionDto> sections) {}

@RestController
class HomeController {
    private static final int SECTION_LIMIT = 12;

    private final CatalogQueryService catalog;
    private final HomePlacementRepository placements;
    private final JdbcTemplate jdbc;

    HomeController(CatalogQueryService catalog, HomePlacementRepository placements, JdbcTemplate jdbc) {
        this.catalog = catalog;
        this.placements = placements;
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/home")
    HomeResponseDto home(
        @RequestHeader(value = "Authorization", required = false) String authorization,
        Authentication authentication
    ) {
        var catalogItems = catalog.homeDramas();
        if (catalogItems.isEmpty()) return new HomeResponseDto(null, List.of());

        var byId = catalogItems.stream().collect(Collectors.toMap(
            CatalogQueryService.HomeDrama::dramaId,
            Function.identity(),
            (first, ignored) -> first,
            LinkedHashMap::new
        ));
        var sections = new ArrayList<HomeSectionDto>();

        addPersonalized(sections, byId, authentication);
        addCurated(sections, byId);
        addRanked(sections, byId);
        addNewest(sections, byId);
        addGenres(sections, catalogItems);

        if (sections.isEmpty()) {
            sections.add(section("DISCOVER", "Descubra novos dramas", catalogItems, null));
        }

        String heroDramaId = curatedHero(byId)
            .or(() -> firstItem(sections, "MOST_WATCHED"))
            .or(() -> firstItem(sections, "NEW_RELEASES"))
            .orElse(catalogItems.getFirst().dramaId());

        return new HomeResponseDto(heroDramaId, sections);
    }

    private void addPersonalized(
        List<HomeSectionDto> sections,
        Map<String, CatalogQueryService.HomeDrama> byId,
        Authentication authentication
    ) {
        UUID userId = authenticatedUser(authentication);
        if (userId == null) return;

        var preferredGenres = jdbc.query("""
            select d.genre
            from playback_event p
            join drama d on d.id = p.drama_id
            where p.user_id = ?
              and p.event_type = 'play'
              and p.created_at >= now() - interval '90 days'
            group by d.genre
            order by count(distinct p.session_id) desc, d.genre
            limit 3
            """, (rs, row) -> rs.getString(1), userId);

        if (preferredGenres.isEmpty()) return;
        var watched = new HashSet<>(jdbc.query("""
            select distinct drama_id::text
            from playback_event
            where user_id = ?
              and created_at >= now() - interval '30 days'
            """, (rs, row) -> rs.getString(1), userId));

        var recommendations = byId.values().stream()
            .filter(d -> preferredGenres.stream().anyMatch(g -> g.equalsIgnoreCase(d.genre())))
            .filter(d -> !watched.contains(d.dramaId()))
            .limit(SECTION_LIMIT)
            .toList();
        addIfNotEmpty(sections, section("FOR_YOU", "Escolhidos para você", recommendations, "PARA VOCÊ"));
    }

    private void addCurated(
        List<HomeSectionDto> sections,
        Map<String, CatalogQueryService.HomeDrama> byId
    ) {
        var rows = placements.findAllByOrderBySectionPositionAscPositionAsc().stream()
            .filter(p -> byId.containsKey(p.dramaId.toString()))
            .toList();
        var grouped = new LinkedHashMap<String, List<HomePlacementEntity>>();
        rows.forEach(p -> grouped.computeIfAbsent(p.sectionKey, ignored -> new ArrayList<>()).add(p));
        grouped.values().forEach(sectionRows -> {
            var first = sectionRows.getFirst();
            addIfNotEmpty(sections, new HomeSectionDto(
                first.sectionKey,
                first.sectionTitle,
                sectionRows.stream()
                    .limit(SECTION_LIMIT)
                    .map(p -> item(byId.get(p.dramaId.toString()), null))
                    .toList()
            ));
        });
    }

    private void addRanked(
        List<HomeSectionDto> sections,
        Map<String, CatalogQueryService.HomeDrama> byId
    ) {
        var ids = jdbc.query("""
            select drama_id::text
            from playback_event
            where event_type = 'play'
              and created_at >= now() - interval '30 days'
            group by drama_id
            order by count(distinct session_id) desc, max(created_at) desc
            limit ?
            """, (rs, row) -> rs.getString(1), SECTION_LIMIT);
        var ranked = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        addIfNotEmpty(sections, section("MOST_WATCHED", "Mais assistidos", ranked, "EM ALTA"));
    }

    private void addNewest(
        List<HomeSectionDto> sections,
        Map<String, CatalogQueryService.HomeDrama> byId
    ) {
        var ids = jdbc.query("""
            select id::text
            from drama
            where status = 'PUBLISHED'
            order by created_at desc, title
            limit ?
            """, (rs, row) -> rs.getString(1), SECTION_LIMIT);
        var newest = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        addIfNotEmpty(sections, section("NEW_RELEASES", "Novidades", newest, "NOVO"));
    }

    private void addGenres(
        List<HomeSectionDto> sections,
        List<CatalogQueryService.HomeDrama> catalogItems
    ) {
        catalogItems.stream()
            .filter(d -> d.genre() != null && !d.genre().isBlank())
            .collect(Collectors.groupingBy(
                CatalogQueryService.HomeDrama::genre,
                LinkedHashMap::new,
                Collectors.toList()
            ))
            .entrySet().stream()
            .sorted(Comparator
                .<Map.Entry<String, List<CatalogQueryService.HomeDrama>>>comparingInt(e -> e.getValue().size())
                .reversed()
                .thenComparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER))
            .limit(6)
            .forEach(entry -> addIfNotEmpty(
                sections,
                section(
                    "GENRE_" + slug(entry.getKey()),
                    entry.getKey(),
                    entry.getValue().stream().limit(SECTION_LIMIT).toList(),
                    null
                )
            ));
    }

    private Optional<String> curatedHero(Map<String, CatalogQueryService.HomeDrama> byId) {
        return placements.findAllByOrderBySectionPositionAscPositionAsc().stream()
            .filter(p -> p.hero && byId.containsKey(p.dramaId.toString()))
            .map(p -> p.dramaId.toString())
            .findFirst();
    }

    private static Optional<String> firstItem(List<HomeSectionDto> sections, String type) {
        return sections.stream()
            .filter(s -> type.equals(s.type()))
            .flatMap(s -> s.items().stream())
            .map(HomeItemDto::dramaId)
            .findFirst();
    }

    private HomeSectionDto section(
        String type,
        String title,
        List<CatalogQueryService.HomeDrama> dramas,
        String badge
    ) {
        return new HomeSectionDto(type, title, dramas.stream().map(d -> item(d, badge)).toList());
    }

    private HomeItemDto item(CatalogQueryService.HomeDrama drama, String badge) {
        return new HomeItemDto(
            drama.dramaId(),
            drama.firstEpisodeId(),
            null,
            badge,
            drama.genre(),
            drama.coverUrl()
        );
    }

    private static void addIfNotEmpty(List<HomeSectionDto> sections, HomeSectionDto section) {
        if (!section.items().isEmpty() && sections.stream().noneMatch(s -> s.type().equals(section.type()))) {
            sections.add(section);
        }
    }

    private static UUID authenticatedUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) return null;
        try {
            return UUID.fromString(authentication.getName());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static String slug(String value) {
        return value.toUpperCase(Locale.ROOT)
            .replaceAll("[^A-Z0-9]+", "_")
            .replaceAll("^_|_$", "");
    }
}
