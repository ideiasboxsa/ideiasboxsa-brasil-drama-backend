package br.com.brasildrama.catalog;

import br.com.brasildrama.media.MediaStorageService;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlaybackEntryMediaObjectContractTest {
    @Test
    void playbackEntryPrefersResolvedMediaObjectUrl() {
        var dramas = mock(DramaRepository.class);
        var episodes = mock(EpisodeRepository.class);
        var media = mock(MediaStorageService.class);
        var dramaId = UUID.randomUUID();
        var drama = new DramaEntity();
        drama.id = dramaId;
        drama.status = DramaStatus.PUBLISHED;

        var episode = new EpisodeEntity();
        episode.id = UUID.randomUUID();
        episode.dramaId = dramaId;
        episode.number = 1;
        episode.free = true;
        episode.coinPrice = 0;
        episode.videoObjectKey = "episodes/d2/ep1.mp4";
        episode.videoUrl = "https://legacy.example/ep1.mp4";

        when(dramas.findById(dramaId)).thenReturn(Optional.of(drama));
        when(episodes.findByDramaIdOrderByNumberAsc(dramaId)).thenReturn(List.of(episode));
        when(media.readUrl("episodes/d2/ep1.mp4")).thenReturn("https://cdn.example/ep1-signed.mp4");

        var entry = new PlaybackEntryApi(dramas, episodes, media).playbackEntry(dramaId);

        assertThat(entry.episodeId()).isEqualTo(episode.id.toString());
        assertThat(entry.videoUrl()).isEqualTo("https://cdn.example/ep1-signed.mp4");
    }
}
