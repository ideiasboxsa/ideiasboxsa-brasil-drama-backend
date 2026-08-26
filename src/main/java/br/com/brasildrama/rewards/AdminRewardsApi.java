package br.com.brasildrama.rewards;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

record AdminRewardsSummary(
    long usersWithRewards,
    long bonusBalance,
    long vipPointsBalance,
    long checkInsToday,
    long checkIns30d,
    long completedMissions,
    long claimedMissions
) {}

record AdminRewardedAdsSummary(
    long created,
    long verified,
    long claimed,
    long expiredUnclaimed
) {}

record AdminRewardMission(
    String id,
    String title,
    String description,
    String rewardType,
    long rewardAmount,
    Long target,
    String triggerType,
    String actionUrl,
    boolean enabled,
    long participants,
    long completed,
    long claimed
) {}

record AdminRewardsView(
    AdminRewardsSummary summary,
    AdminRewardedAdsSummary rewardedAds,
    List<AdminRewardMission> missions
) {}

record AdminRewardMissionUpdate(boolean enabled) {}

@RestController
@RequestMapping("/v1/admin/rewards")
class AdminRewardsApi {
    private final JdbcTemplate jdbc;

    AdminRewardsApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    AdminRewardsView overview() {
        var summary = new AdminRewardsSummary(
            count("select count(distinct user_id) from reward_ledger"),
            count("select coalesce(sum(amount),0) from reward_ledger where ledger_type='BONUS'"),
            count("select coalesce(sum(amount),0) from reward_ledger where ledger_type='VIP_POINTS'"),
            count("select count(*) from daily_check_in where check_in_date=current_date"),
            count("select count(*) from daily_check_in where check_in_date>=current_date-29"),
            count("select count(*) from user_mission where status='COMPLETED'"),
            count("select count(*) from user_mission where status='CLAIMED'")
        );
        var ads = new AdminRewardedAdsSummary(
            count("select count(*) from rewarded_ad_session"),
            count("select count(*) from rewarded_ad_session where verified_at is not null"),
            count("select count(*) from rewarded_ad_session where claimed_at is not null"),
            count("""
                select count(*) from rewarded_ad_session
                where expires_at<now() and claimed_at is null
                """)
        );
        return new AdminRewardsView(summary, ads, missions());
    }

    @PutMapping("/missions/{missionId}")
    AdminRewardMission updateMission(@PathVariable String missionId, @RequestBody AdminRewardMissionUpdate request) {
        if (missionId == null || missionId.isBlank() || missionId.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid mission id");
        }
        int updated = jdbc.update(
            "update reward_mission set enabled=? where id=?",
            request.enabled(),
            missionId
        );
        if (updated == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mission not found");
        }
        return missions().stream()
            .filter(item -> item.id().equals(missionId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mission not found"));
    }

    private List<AdminRewardMission> missions() {
        return jdbc.query("""
            select m.id,m.title,m.description,m.reward_type,m.reward_amount,m.target,m.trigger_type,m.action_url,m.enabled,
                   count(um.user_id) participants,
                   count(*) filter (where um.status='COMPLETED') completed,
                   count(*) filter (where um.status='CLAIMED') claimed
            from reward_mission m
            left join user_mission um on um.mission_id=m.id
            group by m.id,m.title,m.description,m.reward_type,m.reward_amount,m.target,m.trigger_type,m.action_url,m.enabled
            order by m.enabled desc,m.title
            """,
            (rs, row) -> new AdminRewardMission(
                rs.getString("id"),
                rs.getString("title"),
                rs.getString("description"),
                rs.getString("reward_type"),
                rs.getLong("reward_amount"),
                rs.getObject("target") == null ? null : rs.getLong("target"),
                rs.getString("trigger_type"),
                rs.getString("action_url"),
                rs.getBoolean("enabled"),
                rs.getLong("participants"),
                rs.getLong("completed"),
                rs.getLong("claimed")
            )
        );
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
