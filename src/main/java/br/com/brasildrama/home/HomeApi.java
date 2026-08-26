package br.com.brasildrama.home;

import br.com.brasildrama.catalog.CatalogQueryService;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import java.util.stream.Collectors;

record HomeItemDto(String dramaId, String episodeId, Long progressMs, String badge, String subtitle, String imageUrl) {}
record HomeSectionDto(String type, String title, List<HomeItemDto> items) {}
record HomeResponseDto(String heroDramaId, List<HomeSectionDto> sections) {}

@RestController
class HomeController {
    private final CatalogQueryService catalog;
    private final HomePlacementRepository placements;

    HomeController(CatalogQueryService catalog, HomePlacementRepository placements) {
        this.catalog = catalog;
        this.placements = placements;
    }

    @GetMapping("/v1/home")
    HomeResponseDto home(@RequestHeader(value = "Authorization", required = false) String authorization) {
        var catalogItems = catalog.homeDramas();
        var byId = catalogItems.stream().collect(Collectors.toMap(CatalogQueryService.HomeDrama::dramaId, item -> item));
        var rows = placements.findAllByOrderBySectionPositionAscPositionAsc().stream()
            .filter(p -> byId.containsKey(p.dramaId.toString())).toList();

        if (rows.isEmpty()) {
            var items = catalogItems.stream().map(this::item).toList();
            var sections = items.isEmpty() ? List.<HomeSectionDto>of() :
                List.of(new HomeSectionDto("FOR_YOU", "Para você", items));
            return new HomeResponseDto(catalogItems.isEmpty() ? null : catalogItems.getFirst().dramaId(), sections);
        }

        var grouped = new LinkedHashMap<String, List<HomePlacementEntity>>();
        rows.forEach(p -> grouped.computeIfAbsent(p.sectionKey, ignored -> new ArrayList<>()).add(p));
        var sections = grouped.values().stream().map(sectionRows -> {
            var first = sectionRows.getFirst();
            return new HomeSectionDto(first.sectionKey, first.sectionTitle,
                sectionRows.stream().map(p -> item(byId.get(p.dramaId.toString()))).toList());
        }).toList();
        var heroDramaId = rows.stream().filter(p -> p.hero).map(p -> p.dramaId.toString())
            .findFirst().orElse(rows.getFirst().dramaId.toString());
        return new HomeResponseDto(heroDramaId, sections);
    }

    private HomeItemDto item(CatalogQueryService.HomeDrama drama) {
        return new HomeItemDto(drama.dramaId(), drama.firstEpisodeId(), null, null, drama.genre(), drama.coverUrl());
    }
}
