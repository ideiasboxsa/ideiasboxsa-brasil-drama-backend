package br.com.brasildrama.catalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Exclusão administrativa de série preservando aquisições financeiras.
 *
 * <p>Uma série só pode ser removida definitivamente quando nenhum episódio possui
 * entitlement. Históricos editoriais e de consumo que não representam um bem
 * adquirido são limpos na mesma transação. A mídia é removida pelo controller
 * somente depois que esta transação termina com sucesso.</p>
 */
@Service
public class DramaDeletionService {
    private final DramaRepository dramas;
    private final EpisodeRepository episodes;
    private final EpisodeDeletionService episodeDeletion;
    private final JdbcTemplate jdbc;

    DramaDeletionService(
        DramaRepository dramas,
        EpisodeRepository episodes,
        EpisodeDeletionService episodeDeletion,
        JdbcTemplate jdbc
    ) {
        this.dramas = dramas;
        this.episodes = episodes;
        this.episodeDeletion = episodeDeletion;
        this.jdbc = jdbc;
    }

    @Transactional
    public Outcome delete(UUID dramaId) {
        var drama = dramas.findById(dramaId).orElse(null);
        if (drama == null) return Outcome.notFound();

        var dramaEpisodes = episodes.findByDramaIdOrderByNumberAsc(dramaId);
        if (hasEntitlements(dramaId)) return Outcome.blocked("DRAMA_HAS_ENTITLEMENTS");

        var episodeIds = dramaEpisodes.stream().map(episode -> episode.id).toList();

        // Relações que representam personalização/curadoria, e não aquisição.
        jdbc.update("delete from home_placement where drama_id = ?", dramaId);
        jdbc.update("delete from user_favorite where drama_id = ?", dramaId);
        jdbc.update("delete from drama_like where drama_id = ?", dramaId);

        // Analytics não devem impedir manutenção editorial do catálogo.
        jdbc.update("delete from content_event where drama_id = ?", dramaId);
        jdbc.update("delete from playback_event where drama_id = ?", dramaId);

        for (var episode : dramaEpisodes) {
            var outcome = episodeDeletion.delete(dramaId, episode.id);
            if (!outcome.isDeleted()) {
                return Outcome.blocked(outcome.blockedReason() == null ? "DRAMA_DELETE_BLOCKED" : outcome.blockedReason());
            }
        }

        dramas.delete(drama);
        dramas.flush();

        return Outcome.deleted(
            drama.posterObjectKey,
            drama.backdropObjectKey,
            episodeIds
        );
    }

    private boolean hasEntitlements(UUID dramaId) {
        var count = jdbc.queryForObject(
            "select count(*) from episode_entitlement ee join episode e on e.id = ee.episode_id where e.drama_id = ?",
            Long.class,
            dramaId
        );
        return count != null && count > 0;
    }

    public record Outcome(
        Status status,
        String blockedReason,
        String posterObjectKey,
        String backdropObjectKey,
        List<UUID> episodeIds
    ) {
        public enum Status { DELETED, BLOCKED, NOT_FOUND }

        static Outcome deleted(String posterObjectKey, String backdropObjectKey, List<UUID> episodeIds) {
            return new Outcome(Status.DELETED, null, posterObjectKey, backdropObjectKey, List.copyOf(episodeIds));
        }

        static Outcome blocked(String reason) {
            return new Outcome(Status.BLOCKED, reason, null, null, List.of());
        }

        static Outcome notFound() {
            return new Outcome(Status.NOT_FOUND, null, null, null, List.of());
        }

        public boolean isDeleted() { return status == Status.DELETED; }
        public boolean isNotFound() { return status == Status.NOT_FOUND; }
    }
}
