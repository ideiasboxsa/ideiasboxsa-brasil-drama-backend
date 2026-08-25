package br.com.brasildrama.rewards;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class RewardsProgressService {
    private final JdbcTemplate jdbc;

    public RewardsProgressService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void recordEpisodeCompletion(UUID userId, UUID episodeId) {
        int inserted = jdbc.update(
            "insert into episode_completion(user_id,episode_id,completed_at) values (?,?,now()) on conflict (user_id,episode_id) do nothing",
            userId, episodeId
        );
        if (inserted == 0) return;

        Long completedEpisodes = jdbc.queryForObject(
            "select count(*) from episode_completion where user_id=?",
            Long.class, userId
        );
        long progress = completedEpisodes == null ? 0L : completedEpisodes;

        var missions = jdbc.query(
            "select id,target from reward_mission where enabled=true and trigger_type='EPISODE_COMPLETED' and target is not null",
            (rs, rowNum) -> new MissionTarget(rs.getString(1), rs.getLong(2))
        );

        for (var mission : missions) {
            long bounded = Math.min(progress, mission.target());
            String status = bounded >= mission.target() ? "COMPLETED" : "IN_PROGRESS";
            jdbc.update(
                """
                insert into user_mission(user_id,mission_id,progress,status,updated_at)
                values (?,?,?,?,now())
                on conflict (user_id,mission_id) do update
                   set progress = case when user_mission.status='CLAIMED' then user_mission.progress else excluded.progress end,
                       status = case when user_mission.status='CLAIMED' then user_mission.status else excluded.status end,
                       updated_at = case when user_mission.status='CLAIMED' then user_mission.updated_at else now() end
                """,
                userId, mission.id(), bounded, status
            );
        }
    }

    private record MissionTarget(String id, long target) {}
}
