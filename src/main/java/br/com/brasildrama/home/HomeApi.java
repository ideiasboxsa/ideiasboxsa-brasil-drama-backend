package br.com.brasildrama.home;

import br.com.brasildrama.catalog.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

record HomeItemDto(String dramaId, String episodeId, Long progressMs, String badge, String subtitle, String imageUrl) {}
record HomeSectionDto(String type, String title, List<HomeItemDto> items) {}
record HomeResponseDto(String heroDramaId, List<HomeSectionDto> sections) {}

@RestController
class HomeController {
    private final DramaRepository dramas;
    private final EpisodeRepository episodes;

    HomeController(DramaRepository dramas, EpisodeRepository episodes) {
        this.dramas = dramas;
        this.episodes = episodes;
    }

    @GetMapping("/v1/home")
    HomeResponseDto home(@RequestHeader(value = "Authorization", required = false) String authorization) {
        var all = dramas.findAllByOrderByTitleAsc();
        var items = all.stream().map(d -> new HomeItemDto(
            d.id.toString(),
            episodes.findByDramaIdOrderByNumberAsc(d.id).stream().findFirst().map(e -> e.id.toString()).orElse(null),
            null,
            null,
            d.genre,
            d.coverUrl
        )).toList();

        var sections = new ArrayList<HomeSectionDto>();
        if (!items.isEmpty()) sections.add(new HomeSectionDto("FOR_YOU", "Para você", items));
        return new HomeResponseDto(all.isEmpty() ? null : all.getFirst().id.toString(), sections);
    }
}
