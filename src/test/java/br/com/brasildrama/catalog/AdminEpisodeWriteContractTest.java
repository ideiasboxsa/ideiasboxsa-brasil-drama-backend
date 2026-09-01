package br.com.brasildrama.catalog;

import br.com.brasildrama.media.MediaStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Criação, edição e reordenação de episódio. */
class AdminEpisodeWriteContractTest {

    @Test
    void createRejectsDuplicateNumberBeforeHittingTheDatabase() {
        var fixture = new Fixture(DramaStatus.PUBLISHED);
        when(fixture.episodes.existsByDramaIdAndNumber(fixture.dramaId, 2)).thenReturn(true);

        var response = fixture.api().create(fixture.dramaId, request(2));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isEqualTo(Map.of("code", "EPISODE_NUMBER_ALREADY_EXISTS"));
        verify(fixture.episodes, never()).saveAndFlush(any());
    }

    /**
     * O {@code existsBy} acima não fecha a corrida: duas abas do Studio recebem o
     * mesmo "último número + 1" e a segunda só descobre o conflito na constraint.
     * Antes deste tratamento o operador via 500 no lugar de um 409 que a tela já
     * sabe explicar.
     */
    @Test
    void createTranslatesTheUniqueConstraintRaceIntoConflict() {
        var fixture = new Fixture(DramaStatus.PUBLISHED);
        when(fixture.episodes.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_episode_drama_number"));

        var response = fixture.api().create(fixture.dramaId, request(2));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isEqualTo(Map.of("code", "EPISODE_NUMBER_ALREADY_EXISTS"));
    }

    @Test
    void createOnPublishedDramaSucceeds() {
        var fixture = new Fixture(DramaStatus.PUBLISHED);

        var response = fixture.api().create(fixture.dramaId, request(4));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        verify(fixture.episodes).saveAndFlush(any());
    }

    @Test
    void createOnArchivedDramaIsBlocked() {
        var fixture = new Fixture(DramaStatus.ARCHIVED);

        var response = fixture.api().create(fixture.dramaId, request(4));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).isEqualTo(Map.of("code", "DRAMA_ARCHIVED"));
    }

    /** Episódio grátis não carrega preço, qualquer que seja o enviado. */
    @Test
    void freeEpisodeHasNoCoinPrice() {
        var fixture = new Fixture(DramaStatus.DRAFT);
        var existing = episode(fixture.dramaId, 1);
        existing.coinPrice = 30;
        when(fixture.episodes.findById(existing.id)).thenReturn(Optional.of(existing));

        var request = new AdminEpisodeApi.EpisodeRequest(1, "Episódio 1", null, 120, true, 30);
        var response = fixture.api().update(fixture.dramaId, existing.id, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(existing.coinPrice).isZero();
    }

    @Test
    void updateOfEpisodeFromAnotherDramaIsNotFound() {
        var fixture = new Fixture(DramaStatus.PUBLISHED);
        var foreign = episode(UUID.randomUUID(), 1);
        when(fixture.episodes.findById(foreign.id)).thenReturn(Optional.of(foreign));

        assertThat(fixture.api().update(fixture.dramaId, foreign.id, request(1)).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void reorderRejectsAnIncompleteSet() {
        var fixture = new Fixture(DramaStatus.PUBLISHED);
        var first = episode(fixture.dramaId, 1);
        var second = episode(fixture.dramaId, 2);
        when(fixture.episodes.findByDramaIdOrderByNumberAsc(fixture.dramaId)).thenReturn(List.of(first, second));

        var response = fixture.api().reorder(fixture.dramaId, new AdminEpisodeApi.ReorderRequest(List.of(first.id)));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).isEqualTo(Map.of("code", "INVALID_EPISODE_ORDER"));
        verify(fixture.episodes, never()).saveAllAndFlush(any());
    }

    /**
     * A renumeração passa por números negativos para não colidir com a constraint de
     * unicidade no meio do caminho. São dois flushes, e é exatamente por isso que o
     * método precisa ser público: sem a transação valendo, uma falha entre eles
     * deixaria a série com "EP -1", "EP -2" de forma permanente.
     */
    @Test
    void reorderRenumbersThroughNegativesInTwoFlushes() {
        var fixture = new Fixture(DramaStatus.PUBLISHED);
        var first = episode(fixture.dramaId, 1);
        var second = episode(fixture.dramaId, 2);
        var third = episode(fixture.dramaId, 3);
        when(fixture.episodes.findByDramaIdOrderByNumberAsc(fixture.dramaId)).thenReturn(List.of(first, second, third));

        var flushed = new ArrayList<List<Integer>>();
        when(fixture.episodes.saveAllAndFlush(any())).thenAnswer(invocation -> {
            List<EpisodeEntity> batch = invocation.getArgument(0);
            flushed.add(batch.stream().map(episode -> episode.number).toList());
            return batch;
        });

        var response = fixture.api().reorder(fixture.dramaId, new AdminEpisodeApi.ReorderRequest(List.of(third.id, first.id, second.id)));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(flushed).containsExactly(List.of(-1, -2, -3), List.of(1, 2, 3));
        assertThat(third.number).isEqualTo(1);
        assertThat(first.number).isEqualTo(2);
        assertThat(second.number).isEqualTo(3);
    }

    private static AdminEpisodeApi.EpisodeRequest request(int number) {
        return new AdminEpisodeApi.EpisodeRequest(number, "Episódio " + number, "  descrição  ", 120, false, 30);
    }

    private static EpisodeEntity episode(UUID dramaId, int number) {
        var episode = new EpisodeEntity();
        episode.id = UUID.randomUUID();
        episode.dramaId = dramaId;
        episode.number = number;
        episode.title = "Episódio " + number;
        return episode;
    }

    private static final class Fixture {
        final DramaRepository dramas = mock(DramaRepository.class);
        final EpisodeRepository episodes = mock(EpisodeRepository.class);
        final MediaStorageService media = mock(MediaStorageService.class);
        final EpisodeDeletionService deletions = mock(EpisodeDeletionService.class);
        final UUID dramaId = UUID.randomUUID();

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
