package br.com.brasildrama.catalog;

import br.com.brasildrama.media.MediaStorageService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@RestController
@RequestMapping("/v1/admin/dramas/{dramaId}/episodes")
class AdminEpisodeApi {
    private final DramaRepository dramas;
    private final EpisodeRepository episodes;
    private final MediaStorageService media;

    AdminEpisodeApi(DramaRepository dramas, EpisodeRepository episodes, MediaStorageService media) {
        this.dramas = dramas;
        this.episodes = episodes;
        this.media = media;
    }

    @GetMapping
    ResponseEntity<?> list(@PathVariable UUID dramaId) {
        if (!dramas.existsById(dramaId)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(episodes.findByDramaIdOrderByNumberAsc(dramaId).stream().map(this::view).toList());
    }

    @PostMapping
    ResponseEntity<?> create(@PathVariable UUID dramaId, @Valid @RequestBody EpisodeRequest request) {
        var drama = dramas.findById(dramaId).orElse(null);
        if (drama == null) return ResponseEntity.notFound().build();
        if (drama.status == DramaStatus.ARCHIVED) return conflict("DRAMA_ARCHIVED");
        if (episodes.existsByDramaIdAndNumber(dramaId, request.number())) return conflict("EPISODE_NUMBER_ALREADY_EXISTS");

        var episode = new EpisodeEntity();
        episode.dramaId = dramaId;
        apply(episode, request);
        episodes.saveAndFlush(episode);
        return ResponseEntity.status(201).body(view(episode));
    }

    @PutMapping("/{episodeId}")
    ResponseEntity<?> update(@PathVariable UUID dramaId, @PathVariable UUID episodeId, @Valid @RequestBody EpisodeRequest request) {
        var drama = dramas.findById(dramaId).orElse(null);
        if (drama == null) return ResponseEntity.notFound().build();
        if (drama.status == DramaStatus.ARCHIVED) return conflict("DRAMA_ARCHIVED");
        var episode = episodes.findById(episodeId).filter(e -> e.dramaId.equals(dramaId)).orElse(null);
        if (episode == null) return ResponseEntity.notFound().build();
        if (episode.number != request.number() && episodes.existsByDramaIdAndNumber(dramaId, request.number())) return conflict("EPISODE_NUMBER_ALREADY_EXISTS");

        apply(episode, request);
        episodes.saveAndFlush(episode);
        return ResponseEntity.ok(view(episode));
    }

    @PostMapping("/reorder")
    @Transactional
    ResponseEntity<?> reorder(@PathVariable UUID dramaId, @Valid @RequestBody ReorderRequest request) {
        var drama = dramas.findById(dramaId).orElse(null);
        if (drama == null) return ResponseEntity.notFound().build();
        if (drama.status == DramaStatus.ARCHIVED) return conflict("DRAMA_ARCHIVED");

        var current = episodes.findByDramaIdOrderByNumberAsc(dramaId);
        var requestedIds = request.episodeIds();
        if (requestedIds.size() != current.size() || new HashSet<>(requestedIds).size() != current.size()
            || !new HashSet<>(requestedIds).equals(current.stream().map(e -> e.id).collect(java.util.stream.Collectors.toSet()))) {
            return ResponseEntity.badRequest().body(Map.of("code", "INVALID_EPISODE_ORDER"));
        }

        var byId = current.stream().collect(java.util.stream.Collectors.toMap(e -> e.id, e -> e));
        var ordered = requestedIds.stream().map(byId::get).toList();
        for (int index = 0; index < ordered.size(); index++) ordered.get(index).number = -(index + 1);
        episodes.saveAllAndFlush(ordered);
        for (int index = 0; index < ordered.size(); index++) ordered.get(index).number = index + 1;
        episodes.saveAllAndFlush(ordered);
        return ResponseEntity.ok(ordered.stream().map(this::view).toList());
    }

    @DeleteMapping("/{episodeId}")
    ResponseEntity<?> delete(@PathVariable UUID dramaId, @PathVariable UUID episodeId) {
        var drama = dramas.findById(dramaId).orElse(null);
        if (drama == null) return ResponseEntity.notFound().build();
        if (drama.status == DramaStatus.PUBLISHED || drama.status == DramaStatus.ARCHIVED) return conflict("DRAMA_STATE_BLOCKS_EPISODE_DELETE");
        var episode = episodes.findById(episodeId).filter(e -> e.dramaId.equals(dramaId)).orElse(null);
        if (episode == null) return ResponseEntity.notFound().build();
        episodes.delete(episode);
        return ResponseEntity.noContent().build();
    }

    private void apply(EpisodeEntity episode, EpisodeRequest request) {
        episode.number = request.number();
        episode.title = request.title().trim();
        episode.description = request.description() == null ? null : request.description().trim();
        episode.durationSeconds = request.durationSeconds();
        episode.free = request.free();
        episode.coinPrice = request.free() ? 0 : request.coinPrice();
    }

    private EpisodeView view(EpisodeEntity e) {
        var playbackUrl = e.videoObjectKey == null || e.videoObjectKey.isBlank() ? e.videoUrl : media.readUrl(e.videoObjectKey);
        return new EpisodeView(e.id, e.number, e.title, e.description, e.durationSeconds, e.free, e.coinPrice,
            e.videoObjectKey, playbackUrl, playbackUrl != null && !playbackUrl.isBlank(), e.createdAt, e.updatedAt);
    }

    private static ResponseEntity<?> conflict(String code) {
        return ResponseEntity.status(409).body(Map.of("code", code));
    }

    record ReorderRequest(@NotEmpty List<UUID> episodeIds) {}

    record EpisodeRequest(
        @Min(1) int number,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 4000) String description,
        @Min(1) Integer durationSeconds,
        boolean free,
        @Min(0) int coinPrice
    ) {}

    record EpisodeView(UUID id, int number, String title, String description, Integer durationSeconds,
                       boolean free, int coinPrice, String videoObjectKey, String playbackUrl,
                       boolean videoReady, java.time.Instant createdAt, java.time.Instant updatedAt) {}
}
