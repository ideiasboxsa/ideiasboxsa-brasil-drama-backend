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

class PlaybackEntryUnavailableContractTest {
    @Test
    void publishedDramaWithoutPlayableEpisodeReturnsConflict() {
        var dramas = mock(DramaRepository.class);
        var episodes = mock(EpisodeRepository.class);
        var media = mock(MediaStorageService.class);
        var drama = new DramaEntity();
        drama.id = UUID.randomUUID();
        drama.status = DramaStatus.PUBLISHED;

        var episode = new EpisodeEntity();
        episode.id = UUID.randomUUID();
        episode.dramaId = drama.id;
        episode.number = 1;
        episode.title = "Episódio 1";
        episode.free = true;
        episode.coinPrice = 0;
        episode.videoUrl = " ";
        episode.videoObjectKey = null;

        when(dramas.findById(drama.id)).thenReturn(Optional.of(drama));
        when(episodes.findByDramaIdOrderByNumberAsc(drama.id)).thenReturn(List.of(episode));

        assertThatThrownBy(() -> new PlaybackEntryApi(dramas, episodes, media).playbackEntry(drama.id))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(error -> {
                var response = (ResponseStatusException) error;
                assertThat(response.getStatusCode().value()).isEqualTo(409);
                assertThat(response.getReason()).isEqualTo("DRAMA_HAS_NO_PLAYABLE_EPISODE");
            });
    }
}
