package br.com.brasildrama.catalog;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A política de exclusão de episódio, tabela por tabela. O que importa aqui não é
 * o SQL em si, é a separação entre histórico (apagável) e direito de acesso pago
 * (intocável) — e que a verificação do direito venha <b>antes</b> de qualquer
 * escrita, para que um episódio bloqueado saia da transação sem histórico perdido.
 */
class EpisodeDeletionServiceContractTest {

    @Test
    void paidEntitlementBlocksDeleteAndLeavesHistoryIntact() {
        var episodes = mock(EpisodeRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var episode = episode();
        when(episodes.findById(episode.id)).thenReturn(Optional.of(episode));
        when(jdbc.queryForObject(contains("episode_entitlement"), eq(Long.class), eq(episode.id))).thenReturn(1L);

        var outcome = new EpisodeDeletionService(episodes, jdbc).delete(episode.dramaId, episode.id);

        assertThat(outcome.isDeleted()).isFalse();
        assertThat(outcome.blockedReason()).isEqualTo("EPISODE_HAS_ENTITLEMENTS");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(episodes, never()).delete(any());
    }

    @Test
    void watchedEpisodeWithoutEntitlementIsDeletedAlongsideItsHistory() {
        var episodes = mock(EpisodeRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var episode = episode();
        episode.videoObjectKey = "dramas/%s/episodes/%s/video/source.mp4".formatted(episode.dramaId, episode.id);
        when(episodes.findById(episode.id)).thenReturn(Optional.of(episode));
        when(jdbc.queryForObject(contains("episode_entitlement"), eq(Long.class), eq(episode.id))).thenReturn(0L);

        var outcome = new EpisodeDeletionService(episodes, jdbc).delete(episode.dramaId, episode.id);

        assertThat(outcome.isDeleted()).isTrue();
        assertThat(outcome.videoObjectKey())
            .as("a chave sai daqui para que o S3 seja limpo depois do commit")
            .isEqualTo(episode.videoObjectKey);

        verify(jdbc).update(contains("delete from episode_completion"), eq(episode.id));
        verify(jdbc).update(contains("delete from playback_history"), eq(episode.id));
        verify(jdbc).update(contains("update rewarded_ad_session"), eq(episode.id));
        verify(episodes).delete(episode);
        // O flush é o que faz uma FK esquecida estourar aqui, dentro da transação,
        // em vez de no commit — onde viraria 500 sem relação visível com o delete.
        verify(episodes).flush();
    }

    /**
     * A sessão de anúncio recompensado é registro de economia: o usuário assistiu o
     * anúncio e recebeu o crédito. O vínculo com o episódio cai, a sessão fica.
     */
    @Test
    void rewardedAdSessionIsUnlinkedInsteadOfDeleted() {
        var episodes = mock(EpisodeRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var episode = episode();
        when(episodes.findById(episode.id)).thenReturn(Optional.of(episode));

        new EpisodeDeletionService(episodes, jdbc).delete(episode.dramaId, episode.id);

        verify(jdbc).update(contains("set episode_id = null"), eq(episode.id));
        verify(jdbc, never()).update(contains("delete from rewarded_ad_session"), eq(episode.id));
    }

    /** Sem contagem no banco, {@code queryForObject} devolve nulo — não pode virar NPE. */
    @Test
    void absentEntitlementCountIsTreatedAsZero() {
        var episodes = mock(EpisodeRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var episode = episode();
        when(episodes.findById(episode.id)).thenReturn(Optional.of(episode));

        assertThat(new EpisodeDeletionService(episodes, jdbc).delete(episode.dramaId, episode.id).isDeleted()).isTrue();
    }

    @Test
    void episodeOfAnotherDramaIsNotFound() {
        var episodes = mock(EpisodeRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var episode = episode();
        when(episodes.findById(episode.id)).thenReturn(Optional.of(episode));

        var outcome = new EpisodeDeletionService(episodes, jdbc).delete(UUID.randomUUID(), episode.id);

        assertThat(outcome.isNotFound()).isTrue();
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(episodes, never()).delete(any());
    }

    @Test
    void unknownEpisodeIsNotFound() {
        var episodes = mock(EpisodeRepository.class);
        var jdbc = mock(JdbcTemplate.class);
        var episodeId = UUID.randomUUID();
        when(episodes.findById(episodeId)).thenReturn(Optional.empty());

        assertThat(new EpisodeDeletionService(episodes, jdbc).delete(UUID.randomUUID(), episodeId).isNotFound()).isTrue();
    }

    private static EpisodeEntity episode() {
        var episode = new EpisodeEntity();
        episode.id = UUID.randomUUID();
        episode.dramaId = UUID.randomUUID();
        episode.number = 3;
        episode.title = "Episódio 3";
        episode.free = false;
        episode.coinPrice = 30;
        return episode;
    }
}
