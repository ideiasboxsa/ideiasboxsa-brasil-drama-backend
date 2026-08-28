package br.com.brasildrama.catalog;

import br.com.brasildrama.media.MediaStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@RestController
@RequestMapping("/v1/catalog/dramas")
class PlaybackEntryApi {
    private final DramaRepository dramas;
    private final EpisodeRepository episodes;
    private final MediaStorageService media;

    PlaybackEntryApi(DramaRepository dramas, EpisodeRepository episodes, MediaStorageService media) {
        this.dramas = dramas;
        this.episodes = episodes;
        this.media = media;
    }

    @GetMapping("/{dramaId}/playback-entry")
    PlaybackEntryDto playbackEntry(@PathVariable UUID dramaId) {
        dramas.findById(dramaId)
            .filter(drama -> drama.status == DramaStatus.PUBLISHED)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        var episode = episodes.findByDramaIdOrderByNumberAsc(dramaId).stream()
            .filter(this::hasPlayback)
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "DRAMA_HAS_NO_PLAYABLE_EPISODE"));

        return new PlaybackEntryDto(
            dramaId.toString(),
            episode.id.toString(),
            episode.number,
            episode.free,
            episode.coinPrice,
            playbackUrl(episode)
        );
    }

    private boolean hasPlayback(EpisodeEntity episode) {
        return (episode.videoObjectKey != null && !episode.videoObjectKey.isBlank())
            || (episode.videoUrl != null && !episode.videoUrl.isBlank());
    }

    private String playbackUrl(EpisodeEntity episode) {
        return episode.videoObjectKey == null || episode.videoObjectKey.isBlank()
            ? episode.videoUrl
            : media.readUrl(episode.videoObjectKey);
    }

    record PlaybackEntryDto(
        String dramaId,
        String episodeId,
        int episodeNumber,
        boolean free,
        int coinPrice,
        String videoUrl
    ) {}
}
