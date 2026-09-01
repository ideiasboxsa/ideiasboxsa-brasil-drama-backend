package br.com.brasildrama.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Exclusão de episódio com a política de histórico explícita.
 *
 * <p>Antes disto o {@code DELETE} tinha dois defeitos independentes. O primeiro era
 * de regra: bastava a série estar publicada para nenhum episódio poder ser excluído,
 * o que congelava a grade no instante da publicação — num app de drama vertical,
 * publicar e continuar editando é o fluxo normal, e um episódio meio-criado, sem
 * vídeo, ficava lá para sempre. O segundo era técnico: seis tabelas referenciam
 * {@code episode(id)} e quatro delas sem {@code onDelete}, então o primeiro episódio
 * com histórico transformava o delete em violação de integridade e 500.
 *
 * <p>A política separa o que é histórico do que é dinheiro:
 * <ul>
 *   <li>{@code episode_entitlement} — alguém pagou moedas ou assistiu anúncio para
 *       desbloquear. Bloqueia a exclusão, sempre. Apagar o episódio destruiria um bem
 *       adquirido sem devolução.</li>
 *   <li>{@code episode_completion} e {@code playback_history} — histórico de
 *       reprodução, sem contrapartida financeira. Apagados na mesma transação.</li>
 *   <li>{@code rewarded_ad_session} — a coluna é anulável, então a sessão é
 *       preservada para auditoria e só o vínculo com o episódio é desfeito.</li>
 *   <li>{@code playback_event} e {@code content_event} — já são {@code CASCADE}.</li>
 * </ul>
 *
 * <p>O conjunto acima é verificado por {@code EpisodeForeignKeyCoverageContractTest},
 * que lê os changelogs e falha se alguém adicionar uma referência a {@code episode}
 * sem decidir o que ela faz aqui.
 */
@Service
public class EpisodeDeletionService {
    private final EpisodeRepository episodes;
    private final JdbcTemplate jdbc;

    EpisodeDeletionService(EpisodeRepository episodes, JdbcTemplate jdbc) {
        this.episodes = episodes;
        this.jdbc = jdbc;
    }

    /**
     * Público de propósito: o Spring aplica {@code @Transactional} apenas a métodos
     * públicos, e aqui a transação não é decorativa — a limpeza de histórico e a
     * remoção do episódio precisam cair juntas.
     */
    @Transactional
    public Outcome delete(UUID dramaId, UUID episodeId) {
        var episode = episodes.findById(episodeId).filter(e -> e.dramaId.equals(dramaId)).orElse(null);
        if (episode == null) return Outcome.notFound();

        if (count("select count(*) from episode_entitlement where episode_id = ?", episodeId) > 0) {
            return Outcome.blocked("EPISODE_HAS_ENTITLEMENTS");
        }

        var videoObjectKey = episode.videoObjectKey;

        jdbc.update("update rewarded_ad_session set episode_id = null where episode_id = ?", episodeId);
        jdbc.update("delete from episode_completion where episode_id = ?", episodeId);
        jdbc.update("delete from playback_history where episode_id = ?", episodeId);

        episodes.delete(episode);
        // Força a ida ao banco ainda dentro desta transação: uma FK que tenha
        // escapado da política acima precisa estourar aqui, e não no commit, onde
        // viraria 500 sem relação visível com este código.
        episodes.flush();

        return Outcome.deleted(videoObjectKey);
    }

    private long count(String sql, UUID episodeId) {
        var result = jdbc.queryForObject(sql, Long.class, episodeId);
        return result == null ? 0L : result;
    }

    /**
     * Resultado da tentativa. A chave do vídeo sai daqui para que quem chama apague
     * o objeto <b>depois</b> do commit: se o S3 falhar, sobra um objeto órfão barato
     * em vez de um episódio vivo apontando para um vídeo que não existe mais.
     */
    public record Outcome(Status status, String blockedReason, String videoObjectKey) {
        public enum Status { DELETED, BLOCKED, NOT_FOUND }

        static Outcome deleted(String videoObjectKey) { return new Outcome(Status.DELETED, null, videoObjectKey); }
        static Outcome blocked(String reason) { return new Outcome(Status.BLOCKED, reason, null); }
        static Outcome notFound() { return new Outcome(Status.NOT_FOUND, null, null); }

        public boolean isDeleted() { return status == Status.DELETED; }
        public boolean isNotFound() { return status == Status.NOT_FOUND; }
    }
}
