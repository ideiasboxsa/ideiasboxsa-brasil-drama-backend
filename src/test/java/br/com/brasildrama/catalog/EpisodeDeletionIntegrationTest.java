package br.com.brasildrama.catalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A política de exclusão contra o banco migrado de verdade. Os testes com mock provam
 * que o serviço emite o SQL combinado; só o banco prova que o SQL <b>basta</b> — que
 * não sobrou nenhuma FK sem tratamento transformando o {@code DELETE} em 500.
 *
 * <p>Este é o cenário exato que o operador reportou: episódio de série publicada, com
 * histórico de reprodução, que antes não podia ser removido de forma alguma.
 */
@SpringBootTest(properties = {
    "spring.liquibase.contexts=base",
    "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes"
})
class EpisodeDeletionIntegrationTest {
    @Autowired EpisodeDeletionService deletions;
    @Autowired JdbcTemplate jdbc;

    private final List<UUID> dramaIds = new ArrayList<>();
    private final List<UUID> userIds = new ArrayList<>();

    /**
     * O contexto do Spring — e portanto o banco — é compartilhado entre as classes de
     * teste, e este teste grava de propósito fora de transação para que o commit do
     * serviço valha. A limpeza fica aqui, e não no fim de cada método, para que uma
     * assertiva que falhe não deixe linhas para trás poluindo as outras classes.
     */
    @AfterEach
    void cleanUp() {
        for (UUID userId : userIds) {
            jdbc.update("delete from rewarded_ad_session where user_id = ?", userId);
            jdbc.update("delete from episode_entitlement where user_id = ?", userId);
            jdbc.update("delete from episode_completion where user_id = ?", userId);
            jdbc.update("delete from playback_history where user_id = ?", userId);
        }
        for (UUID dramaId : dramaIds) {
            jdbc.update("delete from episode where drama_id = ?", dramaId);
            jdbc.update("delete from drama where id = ?", dramaId);
        }
        userIds.forEach(userId -> jdbc.update("delete from app_user where id = ?", userId));
    }

    @Test
    void watchedEpisodeIsDeletedAndItsHistoryGoesWithIt() {
        var dramaId = insertDrama();
        var episodeId = insertEpisode(dramaId, 1);
        var userId = insertUser();
        insertPlaybackHistory(userId, dramaId, episodeId);
        insertEpisodeCompletion(userId, episodeId);
        var adOperationKey = insertRewardedAdSession(userId, episodeId);

        var outcome = deletions.delete(dramaId, episodeId);

        assertThat(outcome.isDeleted()).isTrue();
        assertThat(exists("select exists(select 1 from episode where id = ?)", episodeId)).isFalse();
        assertThat(exists("select exists(select 1 from playback_history where episode_id = ?)", episodeId)).isFalse();
        assertThat(exists("select exists(select 1 from episode_completion where episode_id = ?)", episodeId)).isFalse();

        // A sessão de anúncio é registro de economia: o usuário assistiu e recebeu o
        // crédito. Só o vínculo com o episódio cai.
        var session = jdbc.queryForMap("select episode_id from rewarded_ad_session where operation_key = ?", adOperationKey);
        assertThat(session.get("episode_id")).isNull();
    }

    @Test
    void paidUnlockBlocksDeleteAndNothingIsLost() {
        var dramaId = insertDrama();
        var episodeId = insertEpisode(dramaId, 1);
        var userId = insertUser();
        insertPlaybackHistory(userId, dramaId, episodeId);
        insertEntitlement(userId, episodeId);

        var outcome = deletions.delete(dramaId, episodeId);

        assertThat(outcome.isDeleted()).isFalse();
        assertThat(outcome.blockedReason()).isEqualTo("EPISODE_HAS_ENTITLEMENTS");
        assertThat(exists("select exists(select 1 from episode where id = ?)", episodeId)).isTrue();
        assertThat(exists("select exists(select 1 from playback_history where episode_id = ?)", episodeId))
            .as("bloqueio precisa sair sem escrever: o histórico continua lá")
            .isTrue();
    }

    /** Episódio sem nenhum histórico — o caso mais comum, o meio-criado sem vídeo. */
    @Test
    void untouchedEpisodeIsDeletedFromAPublishedDrama() {
        var dramaId = insertDrama();
        var episodeId = insertEpisode(dramaId, 7);

        assertThat(deletions.delete(dramaId, episodeId).isDeleted()).isTrue();
        assertThat(exists("select exists(select 1 from episode where id = ?)", episodeId)).isFalse();
    }

    private UUID insertDrama() {
        var dramaId = UUID.randomUUID();
        jdbc.update("insert into drama(id,title,synopsis,genre,status) values (?,?,?,?,'PUBLISHED')",
            dramaId, "Teste exclusão " + dramaId, "sinopse", "DRAMA");
        dramaIds.add(dramaId);
        return dramaId;
    }

    private UUID insertEpisode(UUID dramaId, int number) {
        var episodeId = UUID.randomUUID();
        jdbc.update("insert into episode(id,drama_id,number,title,coin_price,free) values (?,?,?,?,0,true)",
            episodeId, dramaId, number, "Episódio " + number);
        return episodeId;
    }

    private UUID insertUser() {
        var userId = UUID.randomUUID();
        jdbc.update("insert into app_user(id,email) values (?,?)", userId, userId + "@teste.brasildrama");
        userIds.add(userId);
        return userId;
    }

    private void insertPlaybackHistory(UUID userId, UUID dramaId, UUID episodeId) {
        jdbc.update("insert into playback_history(user_id,drama_id,episode_id,position_ms) values (?,?,?,1000)",
            userId, dramaId, episodeId);
    }

    private void insertEpisodeCompletion(UUID userId, UUID episodeId) {
        jdbc.update("insert into episode_completion(user_id,episode_id) values (?,?)", userId, episodeId);
    }

    private void insertEntitlement(UUID userId, UUID episodeId) {
        jdbc.update("insert into episode_entitlement(user_id,episode_id,source,operation_key) values (?,?,'COINS',?)",
            userId, episodeId, "op-" + UUID.randomUUID());
    }

    private String insertRewardedAdSession(UUID userId, UUID episodeId) {
        var operationKey = "ad-" + UUID.randomUUID();
        jdbc.update("""
            insert into rewarded_ad_session(operation_key,user_id,expires_at,reward_type,episode_id)
            values (?,?,now() + interval '1 hour','EPISODE_UNLOCK',?)
            """, operationKey, userId, episodeId);
        return operationKey;
    }

    private boolean exists(String sql, UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(sql, Boolean.class, id));
    }
}
