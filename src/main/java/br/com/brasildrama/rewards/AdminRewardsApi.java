package br.com.brasildrama.rewards;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

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
    String startsAt,
    String endsAt,
    boolean enabled,
    long participants,
    long completed,
    long claimed
) {}

record AdminVipOption(
    String id,
    String label,
    long requiredVipPoints,
    int vipDays,
    boolean enabled,
    int displayOrder,
    long redemptions,
    long pointsSpent
) {}

record AdminRewardTransaction(
    String id,
    String ledgerType,
    long amount,
    String referenceType,
    String referenceId,
    String createdAt
) {}

record AdminRewardFraudSignals(
    long highVelocityUsers24h,
    long repeatedReferences24h,
    long unverifiedAdClaims,
    long verifiedAdsAwaitingClaim
) {}

record AdminCheckInDay(int day, long rewardAmount) {}
record AdminCheckInCycleUpdate(List<AdminCheckInDay> days) {}

record AdminRewardsView(
    AdminRewardsSummary summary,
    AdminRewardedAdsSummary rewardedAds,
    List<AdminRewardMission> missions,
    List<AdminVipOption> vipOptions,
    List<AdminRewardTransaction> recentTransactions,
    List<AdminCheckInDay> checkInCycle,
    AdminRewardFraudSignals fraudSignals
) {}

record AdminRewardMissionUpdate(boolean enabled) {}
record AdminRewardMissionWrite(
    String id,
    String title,
    String description,
    String rewardType,
    long rewardAmount,
    Long target,
    String triggerType,
    String actionUrl,
    String startsAt,
    String endsAt,
    boolean enabled
) {}
record AdminVipOptionUpdate(boolean enabled) {}

@RestController
@RequestMapping("/v1/admin/rewards")
class AdminRewardsApi {
    private static final Set<String> REWARD_TYPES = Set.of("BONUS", "VIP_POINTS");
    private static final Set<String> TRIGGER_TYPES = Set.of("EPISODE_COMPLETED");
    private static final Set<String> ACTION_URLS = Set.of(
        "brasildrama://home",
        "brasildrama://discover",
        "brasildrama://rewards",
        "brasildrama://library",
        "brasildrama://account"
    );

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
        var fraudSignals = new AdminRewardFraudSignals(
            count("""
                select count(*) from (
                    select user_id from reward_ledger
                    where amount>0 and created_at>=now()-interval '24 hours'
                    group by user_id having count(*)>=10
                ) suspicious
                """),
            count("""
                select count(*) from (
                    select user_id,reference_type,reference_id from reward_ledger
                    where reference_id is not null and created_at>=now()-interval '24 hours'
                    group by user_id,reference_type,reference_id having count(*)>1
                ) repeated
                """),
            count("select count(*) from rewarded_ad_session where claimed_at is not null and verified_at is null"),
            count("select count(*) from rewarded_ad_session where verified_at is not null and claimed_at is null and expires_at>now()")
        );
        return new AdminRewardsView(summary, ads, missions(), vipOptions(), recentTransactions(), checkInCycle(), fraudSignals);
    }

    @PutMapping("/check-in/cycle")
    @Transactional
    public List<AdminCheckInDay> updateCheckInCycle(@RequestBody AdminCheckInCycleUpdate request) {
        if (request == null || request.days() == null || request.days().size() < 2 || request.days().size() > 14) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Check-in cycle must have between 2 and 14 days");
        }
        for (int index = 0; index < request.days().size(); index++) {
            var day = request.days().get(index);
            if (day == null || day.day() != index + 1 || day.rewardAmount() <= 0 || day.rewardAmount() > 1_000_000) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid check-in cycle");
            }
        }
        jdbc.update("delete from reward_checkin_cycle");
        request.days().forEach(day -> jdbc.update(
            "insert into reward_checkin_cycle(day_number,reward_amount,enabled) values (?,?,true)",
            day.day(),
            day.rewardAmount()
        ));
        return checkInCycle();
    }

    @PostMapping("/missions")
    @ResponseStatus(HttpStatus.CREATED)
    AdminRewardMission createMission(@RequestBody AdminRewardMissionWrite request) {
        validateMission(request, true);
        try {
            jdbc.update("""
                insert into reward_mission(id,title,description,reward_type,reward_amount,target,trigger_type,action_url,starts_at,ends_at,enabled)
                values (?,?,?,?,?,?,?,?,?,?,?)
                """,
                request.id().trim(),
                request.title().trim(),
                request.description().trim(),
                request.rewardType(),
                request.rewardAmount(),
                request.target(),
                request.triggerType(),
                request.actionUrl(),
                parseDate(request.startsAt()),
                parseDate(request.endsAt()),
                request.enabled()
            );
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mission id already exists");
        }
        return mission(request.id());
    }

    @PutMapping("/missions/{missionId}/details")
    AdminRewardMission updateMissionDetails(@PathVariable String missionId, @RequestBody AdminRewardMissionWrite request) {
        validateId(missionId);
        validateMission(request, false);
        int updated = jdbc.update("""
            update reward_mission
               set title=?,description=?,reward_type=?,reward_amount=?,target=?,trigger_type=?,action_url=?,starts_at=?,ends_at=?,enabled=?
             where id=?
            """,
            request.title().trim(),
            request.description().trim(),
            request.rewardType(),
            request.rewardAmount(),
            request.target(),
            request.triggerType(),
            request.actionUrl(),
            parseDate(request.startsAt()),
            parseDate(request.endsAt()),
            request.enabled(),
            missionId
        );
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mission not found");
        return mission(missionId);
    }

    @PutMapping("/missions/{missionId}")
    AdminRewardMission updateMission(@PathVariable String missionId, @RequestBody AdminRewardMissionUpdate request) {
        validateId(missionId);
        int updated = jdbc.update(
            "update reward_mission set enabled=? where id=?",
            request.enabled(),
            missionId
        );
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Mission not found");
        return mission(missionId);
    }

    @PutMapping("/vip/{optionId}")
    AdminVipOption updateVipOption(@PathVariable String optionId, @RequestBody AdminVipOptionUpdate request) {
        if (optionId == null || optionId.isBlank() || optionId.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid VIP option id");
        }
        int updated = jdbc.update("update vip_redemption_option set enabled=? where id=?", request.enabled(), optionId);
        if (updated == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "VIP option not found");
        return vipOptions().stream()
            .filter(item -> item.id().equals(optionId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "VIP option not found"));
    }

    private void validateMission(AdminRewardMissionWrite request, boolean requireId) {
        if (request == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mission payload is required");
        if (requireId) validateId(request.id());
        if (request.title() == null || request.title().isBlank() || request.title().length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid mission title");
        }
        if (request.description() == null || request.description().isBlank() || request.description().length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid mission description");
        }
        if (!REWARD_TYPES.contains(request.rewardType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid reward type");
        }
        if (request.rewardAmount() <= 0 || request.rewardAmount() > 1_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid reward amount");
        }
        if (request.target() == null || request.target() <= 0 || request.target() > 1_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid mission target");
        }
        if (!TRIGGER_TYPES.contains(request.triggerType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid trigger type");
        }
        if (!ACTION_URLS.contains(request.actionUrl())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid action URL");
        }
        var startsAt = parseDate(request.startsAt());
        var endsAt = parseDate(request.endsAt());
        if (startsAt != null && endsAt != null && !endsAt.isAfter(startsAt)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Mission end must be after start");
        }
    }

    private java.time.OffsetDateTime parseDate(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return java.time.OffsetDateTime.parse(value);
        } catch (java.time.format.DateTimeParseException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid mission schedule");
        }
    }

    private void validateId(String missionId) {
        if (missionId == null || missionId.isBlank() || missionId.length() > 80 || !missionId.matches("[a-z0-9]+(?:-[a-z0-9]+)*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid mission id");
        }
    }

    private AdminRewardMission mission(String missionId) {
        return missions().stream()
            .filter(item -> item.id().equals(missionId))
            .findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Mission not found"));
    }

    private List<AdminRewardMission> missions() {
        return jdbc.query("""
            select m.id,m.title,m.description,m.reward_type,m.reward_amount,m.target,m.trigger_type,m.action_url,m.starts_at,m.ends_at,m.enabled,
                   count(um.user_id) participants,
                   count(*) filter (where um.status='COMPLETED') completed,
                   count(*) filter (where um.status='CLAIMED') claimed
            from reward_mission m
            left join user_mission um on um.mission_id=m.id
            group by m.id,m.title,m.description,m.reward_type,m.reward_amount,m.target,m.trigger_type,m.action_url,m.starts_at,m.ends_at,m.enabled
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
                rs.getObject("starts_at", java.time.OffsetDateTime.class) == null ? null : rs.getObject("starts_at", java.time.OffsetDateTime.class).toString(),
                rs.getObject("ends_at", java.time.OffsetDateTime.class) == null ? null : rs.getObject("ends_at", java.time.OffsetDateTime.class).toString(),
                rs.getBoolean("enabled"),
                rs.getLong("participants"),
                rs.getLong("completed"),
                rs.getLong("claimed")
            )
        );
    }

    private List<AdminVipOption> vipOptions() {
        return jdbc.query("""
            select o.id,o.label,o.required_vip_points,o.vip_days,o.enabled,o.display_order,
                   count(r.id) redemptions,coalesce(sum(r.points_spent),0) points_spent
            from vip_redemption_option o
            left join vip_redemption r on r.option_id=o.id
            group by o.id,o.label,o.required_vip_points,o.vip_days,o.enabled,o.display_order
            order by o.display_order,o.id
            """,
            (rs, row) -> new AdminVipOption(
                rs.getString("id"),
                rs.getString("label"),
                rs.getLong("required_vip_points"),
                rs.getInt("vip_days"),
                rs.getBoolean("enabled"),
                rs.getInt("display_order"),
                rs.getLong("redemptions"),
                rs.getLong("points_spent")
            )
        );
    }

    private List<AdminCheckInDay> checkInCycle() {
        return jdbc.query(
            "select day_number,reward_amount from reward_checkin_cycle where enabled=true order by day_number",
            (rs, row) -> new AdminCheckInDay(rs.getInt(1), rs.getLong(2))
        );
    }

    private List<AdminRewardTransaction> recentTransactions() {
        return jdbc.query("""
            select id,ledger_type,amount,reference_type,reference_id,created_at
              from reward_ledger
             order by created_at desc,id desc
             limit 30
            """,
            (rs, row) -> new AdminRewardTransaction(
                rs.getObject("id").toString(),
                rs.getString("ledger_type"),
                rs.getLong("amount"),
                rs.getString("reference_type"),
                rs.getString("reference_id"),
                rs.getObject("created_at", java.time.OffsetDateTime.class).toString()
            )
        );
    }

    private long count(String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }
}
