package br.com.brasildrama.catalog;

import br.com.brasildrama.media.MediaStorageService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
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

        if (!validPricing(episode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PLAYBACK_PRICE_INVALID");
        }

        var resolvedPlaybackUrl = playbackUrl(episode);
        if (!securePlaybackUrl(resolvedPlaybackUrl)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PLAYBACK_URL_UNAVAILABLE");
        }

        return new PlaybackEntryDto(
            dramaId.toString(),
            episode.id.toString(),
            episode.number,
            episode.free,
            episode.coinPrice,
            resolvedPlaybackUrl.trim()
        );
    }

    private boolean hasPlayback(EpisodeEntity episode) {
        return (episode.videoObjectKey != null && !episode.videoObjectKey.isBlank())
            || (episode.videoUrl != null && !episode.videoUrl.isBlank());
    }

    private boolean validPricing(EpisodeEntity episode) {
        return episode.coinPrice >= 0
            && ((episode.free && episode.coinPrice == 0) || (!episode.free && episode.coinPrice > 0));
    }

    private String playbackUrl(EpisodeEntity episode) {
        return episode.videoObjectKey == null || episode.videoObjectKey.isBlank()
            ? episode.videoUrl
            : media.readUrl(episode.videoObjectKey);
    }

    private boolean securePlaybackUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) return false;
        try {
            var uri = URI.create(rawUrl.trim());
            return "https".equalsIgnoreCase(uri.getScheme())
                && uri.getHost() != null
                && !uri.getHost().isBlank()
                && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443);
        } catch (IllegalArgumentException ex) {
            return false;
        }
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
