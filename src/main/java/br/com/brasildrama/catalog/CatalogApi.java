package br.com.brasildrama.catalog;

import br.com.brasildrama.media.MediaStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

record DramaSummaryDto(String id, String title, String synopsis, String genre, String coverUrl, String backdropUrl) {}
record EpisodeDto(String id, int number, String title, int coinPrice, boolean free, String videoUrl) {}
record DramaDetailDto(String id, String title, String synopsis, String genre, String coverUrl, String backdropUrl, List<EpisodeDto> episodes) {}
record CategoryDto(String slug, String name, int order) {}
record SearchResponseDto(List<DramaSummaryDto> items, int total, int limit, int offset) {}

@RestController
@RequestMapping("/v1/catalog")
class CatalogController {
    private final DramaRepository dramas;
    private final EpisodeRepository episodes;
    private final MediaStorageService media;

    CatalogController(DramaRepository dramas, EpisodeRepository episodes, MediaStorageService media) {
        this.dramas = dramas;
        this.episodes = episodes;
        this.media = media;
    }

    @GetMapping("/dramas")
    List<DramaSummaryDto> dramas() {
        return dramas.findAllByOrderByTitleAsc().stream().map(this::summary).toList();
    }

    @GetMapping("/dramas/{dramaId}")
    DramaDetailDto drama(@PathVariable UUID dramaId) {
        var drama = dramas.findById(dramaId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var eps = episodes.findByDramaIdOrderByNumberAsc(dramaId).stream().map(this::episode).toList();
        return new DramaDetailDto(drama.id.toString(), drama.title, drama.synopsis, drama.genre, posterUrl(drama), media.readUrl(drama.backdropObjectKey), eps);
    }

    @GetMapping("/search")
    SearchResponseDto search(@RequestParam("q") String q, @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);
        var all = dramas.findByTitleContainingIgnoreCaseOrSynopsisContainingIgnoreCaseOrGenreContainingIgnoreCaseOrderByTitleAsc(q, q, q).stream().map(this::summary).toList();
        int from = Math.min(safeOffset, all.size());
        int to = Math.min(from + safeLimit, all.size());
        return new SearchResponseDto(all.subList(from, to), all.size(), safeLimit, safeOffset);
    }

    @GetMapping("/categories")
    List<CategoryDto> categories() {
        return dramas.findAll().stream().map(d -> d.genre).filter(Objects::nonNull).map(String::trim).filter(s -> !s.isBlank()).distinct().sorted(String.CASE_INSENSITIVE_ORDER).map(name -> new CategoryDto(slug(name), name, 0)).toList();
    }

    @GetMapping("/categories/{slug}/dramas")
    List<DramaSummaryDto> byCategory(@PathVariable String slug) {
        return dramas.findAllByOrderByTitleAsc().stream().filter(d -> slug(d.genre).equalsIgnoreCase(slug)).map(this::summary).toList();
    }

    private DramaSummaryDto summary(DramaEntity d) { return new DramaSummaryDto(d.id.toString(), d.title, d.synopsis, d.genre, posterUrl(d), media.readUrl(d.backdropObjectKey)); }
    private String posterUrl(DramaEntity d) { return d.posterObjectKey == null || d.posterObjectKey.isBlank() ? d.coverUrl : media.readUrl(d.posterObjectKey); }
    private EpisodeDto episode(EpisodeEntity e) { return new EpisodeDto(e.id.toString(), e.number, e.title, e.coinPrice, e.free, e.videoUrl); }
    private static String slug(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", ""); }
}
