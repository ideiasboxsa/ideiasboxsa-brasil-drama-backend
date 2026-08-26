package br.com.brasildrama.catalog;

import br.com.brasildrama.media.MediaStorageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.text.Normalizer;
import java.util.*;

@RestController
@RequestMapping("/v1/admin/dramas")
class AdminDramaApi {
    private final DramaRepository dramas;
    private final EpisodeRepository episodes;
    private final MediaStorageService media;

    AdminDramaApi(DramaRepository dramas, EpisodeRepository episodes, MediaStorageService media) {
        this.dramas = dramas;
        this.episodes = episodes;
        this.media = media;
    }

    @GetMapping
    List<AdminDramaSummary> list() {
        return dramas.findAllByOrderByTitleAsc().stream().map(this::summary).toList();
    }

    @GetMapping("/{id}")
    ResponseEntity<?> get(@PathVariable UUID id) {
        return dramas.findById(id)
            .<ResponseEntity<?>>map(drama -> ResponseEntity.ok(detail(drama)))
            .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    ResponseEntity<?> create(@Valid @RequestBody CreateDramaRequest request) {
        var slug = uniqueSlug(request.slug() == null || request.slug().isBlank() ? request.title() : request.slug(), null);
        var drama = new DramaEntity();
        drama.title = request.title().trim();
        drama.synopsis = request.synopsis().trim();
        drama.genre = request.genre().trim();
        drama.coverUrl = null;
        drama.slug = slug;
        drama.status = DramaStatus.DRAFT;
        dramas.saveAndFlush(drama);
        return ResponseEntity.status(201).body(detail(drama));
    }

    @PutMapping("/{id}")
    ResponseEntity<?> update(@PathVariable UUID id, @Valid @RequestBody UpdateDramaRequest request) {
        var drama = dramas.findById(id).orElse(null);
        if (drama == null) return ResponseEntity.notFound().build();
        if (drama.status == DramaStatus.ARCHIVED) return ResponseEntity.status(409).body(Map.of("code", "DRAMA_ARCHIVED"));

        drama.title = request.title().trim();
        drama.synopsis = request.synopsis().trim();
        drama.genre = request.genre().trim();
        if (request.slug() != null && !request.slug().isBlank()) drama.slug = uniqueSlug(request.slug(), drama.id);
        dramas.saveAndFlush(drama);
        return ResponseEntity.ok(detail(drama));
    }

    @PostMapping("/{id}/status")
    ResponseEntity<?> changeStatus(@PathVariable UUID id, @Valid @RequestBody ChangeDramaStatusRequest request) {
        var drama = dramas.findById(id).orElse(null);
        if (drama == null) return ResponseEntity.notFound().build();

        final DramaStatus target;
        try { target = DramaStatus.valueOf(request.status().trim().toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("code", "INVALID_DRAMA_STATUS")); }

        var validation = validateTransition(drama, target);
        if (!validation.isEmpty()) return ResponseEntity.status(409).body(Map.of("code", "DRAMA_NOT_READY", "reasons", validation));

        drama.status = target;
        dramas.saveAndFlush(drama);
        return ResponseEntity.ok(detail(drama));
    }

    private List<String> validateTransition(DramaEntity drama, DramaStatus target) {
        var reasons = new ArrayList<String>();
        if (target == DramaStatus.READY || target == DramaStatus.PUBLISHED) {
            if (drama.title == null || drama.title.isBlank()) reasons.add("TITLE_REQUIRED");
            if (drama.synopsis == null || drama.synopsis.isBlank()) reasons.add("SYNOPSIS_REQUIRED");
            if (drama.genre == null || drama.genre.isBlank()) reasons.add("GENRE_REQUIRED");
            if (drama.slug == null || drama.slug.isBlank()) reasons.add("SLUG_REQUIRED");
        }
        if (target == DramaStatus.PUBLISHED) {
            if ((drama.posterObjectKey == null || drama.posterObjectKey.isBlank()) && (drama.coverUrl == null || drama.coverUrl.isBlank())) reasons.add("POSTER_REQUIRED");
            var dramaEpisodes = episodes.findByDramaIdOrderByNumberAsc(drama.id);
            if (dramaEpisodes.isEmpty()) reasons.add("EPISODE_REQUIRED");
            if (dramaEpisodes.stream().anyMatch(ep -> (ep.videoObjectKey == null || ep.videoObjectKey.isBlank()) && (ep.videoUrl == null || ep.videoUrl.isBlank()))) reasons.add("EPISODE_VIDEO_REQUIRED");
        }
        return reasons;
    }

    private String uniqueSlug(String source, UUID currentId) {
        var base = slugify(source);
        var candidate = base;
        var suffix = 2;
        while (slugUsedByAnother(candidate, currentId)) candidate = base + "-" + suffix++;
        return candidate;
    }

    private boolean slugUsedByAnother(String slug, UUID currentId) {
        return dramas.findAll().stream().anyMatch(d -> d.slug != null && d.slug.equalsIgnoreCase(slug) && !d.id.equals(currentId));
    }

    private static String slugify(String source) {
        var normalized = Normalizer.normalize(source, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-|-$)", "");
        return normalized.isBlank() ? UUID.randomUUID().toString() : normalized;
    }

    private AdminDramaSummary summary(DramaEntity drama) {
        return new AdminDramaSummary(drama.id, drama.title, drama.slug, drama.genre, drama.status.name(), posterUrl(drama), media.readUrl(drama.backdropObjectKey), drama.updatedAt);
    }

    private AdminDramaDetail detail(DramaEntity drama) {
        return new AdminDramaDetail(drama.id, drama.title, drama.slug, drama.synopsis, drama.genre, drama.status.name(), posterUrl(drama), media.readUrl(drama.backdropObjectKey), drama.posterObjectKey, drama.backdropObjectKey, drama.createdAt, drama.updatedAt);
    }

    private String posterUrl(DramaEntity drama) {
        return drama.posterObjectKey == null || drama.posterObjectKey.isBlank() ? drama.coverUrl : media.readUrl(drama.posterObjectKey);
    }

    record CreateDramaRequest(
        @NotBlank @Size(max = 180) String title,
        @NotBlank @Size(max = 4000) String synopsis,
        @NotBlank @Size(max = 120) String genre,
        @Size(max = 180) String slug
    ) {}

    record UpdateDramaRequest(
        @NotBlank @Size(max = 180) String title,
        @NotBlank @Size(max = 4000) String synopsis,
        @NotBlank @Size(max = 120) String genre,
        @Size(max = 180) String slug
    ) {}

    record ChangeDramaStatusRequest(@NotBlank String status) {}

    record AdminDramaSummary(UUID id, String title, String slug, String genre, String status, String coverUrl, String backdropUrl, java.time.Instant updatedAt) {}
    record AdminDramaDetail(UUID id, String title, String slug, String synopsis, String genre, String status, String coverUrl, String backdropUrl, String posterObjectKey, String backdropObjectKey, java.time.Instant createdAt, java.time.Instant updatedAt) {}
}
