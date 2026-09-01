package br.com.brasildrama.catalog;

import br.com.brasildrama.media.MediaStorageService;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * O {@code DELETE} de episódio visto de fora. O primeiro teste é guarda de
 * regressão de um defeito real: a regra antiga bloqueava a exclusão em série
 * publicada, e como toda série no ar está publicada, o operador recebia 409 ao
 * tentar remover qualquer episódio — inclusive um meio-criado, sem vídeo e sem
 * histórico.
 */
class AdminEpisodeDeleteContractTest {

    @Test
    void publishedDramaAllowsEpisodeDelete() {
        var fixture = new Fixture(DramaStatus.PUBLISHED);
        var videoObjectKey = "dramas/%s/episodes/%s/video/source.mp4".formatted(fixture.dramaId, fixture.episodeId);
        when(fixture.deletions.delete(fixture.dramaId, fixture.episodeId))
            .thenReturn(new EpisodeDeletionService.Outcome(EpisodeDeletionService.Outcome.Status.DELETED, null, videoObjectKey));

        var response = fixture.api().delete(fixture.dramaId, fixture.episodeId);

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        verify(fixture.media).deleteEpisodeVideos(fixture.dramaId, fixture.episodeId);
    }

    @Test
    void draftDramaAllowsEpisodeDelete() {
        var fixture = new Fixture(DramaStatus.DRAFT);
        when(fixture.deletions.delete(fixture.dramaId, fixture.episodeId))
            .thenReturn(new EpisodeDeletionService.Outcome(EpisodeDeletionService.Outcome.Status.DELETED, null, null));

        assertThat(fixture.api().delete(fixture.dramaId, fixture.episodeId).getStatusCode().value()).isEqualTo(204);
    }

    /** Arquivar é retirar o acervo de operação, não prepará-lo para edição. */
    @Test
    void archivedDramaIsFrozen() {
        var fixture = new Fixture(DramaStatus.ARCHIVED);

        var response = fixture.api().delete(fixture.dramaId, fixture.episodeId);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isEqualTo(Map.of("code", "DRAMA_ARCHIVED"));
        verifyNoInteractions(fixture.deletions);
        verify(fixture.media, never()).deleteEpisodeVideos(any(), any());
    }

    @Test
    void blockedOutcomeKeepsTheVideoInTheBucket() {
        var fixture = new Fixture(DramaStatus.PUBLISHED);
        when(fixture.deletions.delete(fixture.dramaId, fixture.episodeId))
            .thenReturn(new EpisodeDeletionService.Outcome(EpisodeDeletionService.Outcome.Status.BLOCKED, "EPISODE_HAS_ENTITLEMENTS", null));

        var response = fixture.api().delete(fixture.dramaId, fixture.episodeId);

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isEqualTo(Map.of("code", "EPISODE_HAS_ENTITLEMENTS"));
        verify(fixture.media, never()).deleteEpisodeVideos(any(), any());
    }

    @Test
    void unknownEpisodeDoesNotTouchTheBucket() {
        var fixture = new Fixture(DramaStatus.PUBLISHED);
        when(fixture.deletions.delete(fixture.dramaId, fixture.episodeId))
            .thenReturn(new EpisodeDeletionService.Outcome(EpisodeDeletionService.Outcome.Status.NOT_FOUND, null, null));

        assertThat(fixture.api().delete(fixture.dramaId, fixture.episodeId).getStatusCode().value()).isEqualTo(404);
        verify(fixture.media, never()).deleteEpisodeVideos(any(), any());
    }

    @Test
    void unknownDramaIsNotFound() {
        var dramas = mock(DramaRepository.class);
        var episodes = mock(EpisodeRepository.class);
        var media = mock(MediaStorageService.class);
        var deletions = mock(EpisodeDeletionService.class);
        var dramaId = UUID.randomUUID();
        when(dramas.findById(dramaId)).thenReturn(Optional.empty());

        var response = new AdminEpisodeApi(dramas, episodes, media, deletions).delete(dramaId, UUID.randomUUID());

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        verifyNoInteractions(deletions);
    }

    private static final class Fixture {
        final DramaRepository dramas = mock(DramaRepository.class);
        final EpisodeRepository episodes = mock(EpisodeRepository.class);
        final MediaStorageService media = mock(MediaStorageService.class);
        final EpisodeDeletionService deletions = mock(EpisodeDeletionService.class);
        final UUID dramaId = UUID.randomUUID();
        final UUID episodeId = UUID.randomUUID();

        Fixture(DramaStatus status) {
            var drama = new DramaEntity();
            drama.id = dramaId;
            drama.status = status;
            when(dramas.findById(dramaId)).thenReturn(Optional.of(drama));
        }

        AdminEpisodeApi api() {
            return new AdminEpisodeApi(dramas, episodes, media, deletions);
        }
    }
}
