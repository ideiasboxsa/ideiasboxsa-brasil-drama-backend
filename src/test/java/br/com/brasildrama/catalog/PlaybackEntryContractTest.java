package br.com.brasildrama.catalog;

import br.com.brasildrama.media.MediaStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaybackEntryContractTest {
    @Test
    void publishedDramaReturnsFirstPlayableEpisode() {
        var dramas = mock(DramaRepository.class);
        var episodes = mock(EpisodeRepository.class);
        var media = mock(MediaStorageService.class);
        var drama = drama(DramaStatus.PUBLISHED);
        var missingVideo = episode(drama.id, 1, " ");
        var playable = episode(drama.id, 2, "https://example.com/ep2.mp4");

        when(dramas.findById(drama.id)).thenReturn(Optional.of(drama));
        when(episodes.findByDramaIdOrderByNumberAsc(drama.id)).thenReturn(List.of(missingVideo, playable));

        var entry = new PlaybackEntryApi(dramas, episodes, media).playbackEntry(drama.id);

        assertThat(entry.dramaId()).isEqualTo(drama.id.toString());
        assertThat(entry.episodeId()).isEqualTo(playable.id.toString());
        assertThat(entry.episodeNumber()).isEqualTo(2);
        assertThat(entry.videoUrl()).isEqualTo("https://example.com/ep2.mp4");
    }

    @Test
    void publishedDramaWithoutPlayableEpisodeReturnsConflict() {
        var dramas = mock(DramaRepository.class);
        var episodes = mock(EpisodeRepository.class);
        var media = mock(MediaStorageService.class);
        var drama = drama(DramaStatus.PUBLISHED);
        when(dramas.findById(drama.id)).thenReturn(Optional.of(drama));
        when(episodes.findByDramaIdOrderByNumberAsc(drama.id)).thenReturn(List.of(episode(drama.id, 1, " ")));

        assertThatThrownBy(() -> new PlaybackEntryApi(dramas, episodes, media).playbackEntry(drama.id))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> {
                var response = (ResponseStatusException) error;
                assertThat(response.getStatusCode().value()).isEqualTo(409);
                assertThat(response.getReason()).isEqualTo("DRAMA_HAS_NO_PLAYABLE_EPISODE");
            });
    }

    @Test
    void insecurePlaybackUrlsReturnConflict() {
        for (String url : List.of(
            "http://cdn.example.com/ep.mp4",
            "https://user:secret@cdn.example.com/ep.mp4",
            "https://cdn.example.com:8443/ep.mp4"
        )) {
            var dramas = mock(DramaRepository.class);
            var episodes = mock(EpisodeRepository.class);
            var media = mock(MediaStorageService.class);
            var drama = drama(DramaStatus.PUBLISHED);
            when(dramas.findById(drama.id)).thenReturn(Optional.of(drama));
            when(episodes.findByDramaIdOrderByNumberAsc(drama.id)).thenReturn(List.of(episode(drama.id, 1, url)));

            assertThatThrownBy(() -> new PlaybackEntryApi(dramas, episodes, media).playbackEntry(drama.id))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> {
                    var response = (ResponseStatusException) error;
                    assertThat(response.getStatusCode().value()).isEqualTo(409);
                    assertThat(response.getReason()).isEqualTo("PLAYBACK_URL_UNAVAILABLE");
                });
        }
    }

    @Test
    void draftDramaCannotExposePlaybackEntry() {
        var dramas = mock(DramaRepository.class);
        var episodes = mock(EpisodeRepository.class);
        var media = mock(MediaStorageService.class);
        var drama = drama(DramaStatus.DRAFT);
        when(dramas.findById(drama.id)).thenReturn(Optional.of(drama));

        assertThatThrownBy(() -> new PlaybackEntryApi(dramas, episodes, media).playbackEntry(drama.id))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> assertThat(((ResponseStatusException) error).getStatusCode().value()).isEqualTo(404));
    }

    private static DramaEntity drama(DramaStatus status) {
        var drama = new DramaEntity();
        drama.id = UUID.randomUUID();
        drama.status = status;
        return drama;
    }

    private static EpisodeEntity episode(UUID dramaId, int number, String videoUrl) {
        var episode = new EpisodeEntity();
        episode.id = UUID.randomUUID();
        episode.dramaId = dramaId;
        episode.number = number;
        episode.title = "Episódio " + number;
        episode.free = true;
        episode.coinPrice = 0;
        episode.videoUrl = videoUrl;
        return episode;
    }
}
