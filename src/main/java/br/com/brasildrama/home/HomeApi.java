package br.com.brasildrama.home;

import br.com.brasildrama.catalog.CatalogQueryService;
import org.springframework.web.bind.annotation.*;
import java.util.*;

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
        var byId = catalogItems.stream().collect(java.util.stream.Collectors.toMap(CatalogQueryService.HomeDrama::dramaId, item -> item));
        var curated = placements.findAllByOrderByPositionAsc().stream().map(p -> byId.get(p.dramaId.toString())).filter(Objects::nonNull).toList();
        var all = curated.isEmpty() ? catalogItems : curated;
        var items = all.stream().map(d -> new HomeItemDto(
            d.dramaId(),
            d.firstEpisodeId(),
            null,
            null,
            d.genre(),
            d.coverUrl()
        )).toList();

        var sections = new ArrayList<HomeSectionDto>();
        if (!items.isEmpty()) sections.add(new HomeSectionDto("FOR_YOU", "Para você", items));
        return new HomeResponseDto(all.isEmpty() ? null : all.getFirst().dramaId(), sections);
    }
}
