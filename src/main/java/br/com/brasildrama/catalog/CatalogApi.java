package br.com.brasildrama.catalog;

import br.com.brasildrama.media.MediaStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

record DramaSummaryDto(String id, String title, String synopsis, String genre, String coverUrl, String backdropUrl) {}
record EpisodeDto(String id, int number, String title, String description, Integer durationSeconds, int coinPrice, boolean free, String videoUrl) {}
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
        return dramas.findByStatusOrderByTitleAsc(DramaStatus.PUBLISHED).stream().map(this::summary).toList();
    }

    @GetMapping("/dramas/{dramaId}")
    DramaDetailDto drama(@PathVariable UUID dramaId) {
        var drama = dramas.findById(dramaId).filter(d -> d.status == DramaStatus.PUBLISHED).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        var eps = episodes.findByDramaIdOrderByNumberAsc(dramaId).stream().map(this::episode).toList();
        return new DramaDetailDto(drama.id.toString(), drama.title, drama.synopsis, drama.genre, posterUrl(drama), media.readUrl(drama.backdropObjectKey), eps);
    }

    @GetMapping("/search")
    SearchResponseDto search(@RequestParam("q") String q, @RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);
        var query = normalize(q);
        var all = dramas.findByStatusOrderByTitleAsc(DramaStatus.PUBLISHED).stream()
            .filter(d -> normalize(d.title).contains(query) || normalize(d.synopsis).contains(query) || normalize(d.genre).contains(query))
            .map(this::summary).toList();
        int from = Math.min(safeOffset, all.size());
        int to = Math.min(from + safeLimit, all.size());
        return new SearchResponseDto(all.subList(from, to), all.size(), safeLimit, safeOffset);
    }

    @GetMapping("/categories")
    List<CategoryDto> categories() {
        return dramas.findByStatusOrderByTitleAsc(DramaStatus.PUBLISHED).stream()
            .map(d -> d.genre)
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .distinct()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .map(name -> new CategoryDto(slug(name), name, 0))
            .toList();
    }

    @GetMapping("/categories/{slug}/dramas")
    List<DramaSummaryDto> byCategory(@PathVariable String slug) {
        return dramas.findByStatusOrderByTitleAsc(DramaStatus.PUBLISHED).stream()
            .filter(d -> slug(d.genre).equalsIgnoreCase(slug))
            .map(this::summary)
            .toList();
    }

    private DramaSummaryDto summary(DramaEntity d) { return new DramaSummaryDto(d.id.toString(), d.title, d.synopsis, d.genre, posterUrl(d), media.readUrl(d.backdropObjectKey)); }
    private String posterUrl(DramaEntity d) { return d.posterObjectKey == null || d.posterObjectKey.isBlank() ? d.coverUrl : media.readUrl(d.posterObjectKey); }
    private EpisodeDto episode(EpisodeEntity e) { return new EpisodeDto(e.id.toString(), e.number, e.title, e.description, e.durationSeconds, e.coinPrice, e.free, e.videoObjectKey == null || e.videoObjectKey.isBlank() ? e.videoUrl : media.readUrl(e.videoObjectKey)); }
    private static String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }
    private static String slug(String value) { return normalize(value).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", ""); }
}
