package br.com.brasildrama.catalog;

import br.com.brasildrama.media.MediaStorageService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CatalogPublishedDramaPlaybackContractTest {
    @Test
    void publishedDramaReturnsOrderedPlayableEpisodes() {
        var dramas = mock(DramaRepository.class);
        var episodes = mock(EpisodeRepository.class);
        var media = mock(MediaStorageService.class);
        var drama = new DramaEntity();
        drama.id = UUID.randomUUID();
        drama.title = "Drama D2";
        drama.synopsis = "Fluxo principal";
        drama.genre = "Romance";
        drama.status = DramaStatus.PUBLISHED;
        drama.coverUrl = "https://example.com/poster.jpg";
        drama.createdAt = Instant.now();
        drama.updatedAt = Instant.now();

        var ep1 = episode(drama.id, 1, "https://example.com/ep1.mp4");
        var ep2 = episode(drama.id, 2, "https://example.com/ep2.mp4");
        when(dramas.findById(drama.id)).thenReturn(Optional.of(drama));
        when(episodes.findByDramaIdOrderByNumberAsc(drama.id)).thenReturn(List.of(ep1, ep2));

        var detail = new CatalogController(dramas, episodes, media).drama(drama.id);

        assertThat(detail.id()).isEqualTo(drama.id.toString());
        assertThat(detail.episodes()).extracting(EpisodeDto::number).containsExactly(1, 2);
        assertThat(detail.episodes()).extracting(EpisodeDto::videoUrl)
            .containsExactly("https://example.com/ep1.mp4", "https://example.com/ep2.mp4");
    }

    private static EpisodeEntity episode(UUID dramaId, int number, String videoUrl) {
        var episode = new EpisodeEntity();
        episode.id = UUID.randomUUID();
        episode.dramaId = dramaId;
        episode.number = number;
        episode.title = "Episódio " + number;
        episode.description = "D2";
        episode.durationSeconds = 60;
        episode.coinPrice = 0;
        episode.free = true;
        episode.videoUrl = videoUrl;
        episode.createdAt = Instant.now();
        episode.updatedAt = Instant.now();
        return episode;
    }
}
