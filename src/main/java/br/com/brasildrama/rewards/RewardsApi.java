package br.com.brasildrama.rewards;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Date;
import java.time.*;
import java.util.*;

record RewardsCheckInDayDto(int day, long bonus, boolean claimed) {}
record RewardsCheckInDto(int streakDays, int currentDay, boolean claimedToday, boolean eligible, List<RewardsCheckInDayDto> days) {}
record RewardMissionDto(String id, String title, String description, String rewardType, long rewardAmount, Long progress, Long target, String status, String actionUrl) {}
record VipRedemptionDto(String id, String label, long requiredVipPoints, int vipDays, boolean enabled) {}
record RewardTransactionDto(String id, String ledgerType, long amount, String referenceType, String referenceId, String createdAt) {}
record RewardsOverviewDto(Long bonusBalance, Long vipPointsBalance, RewardsCheckInDto checkIn, List<RewardMissionDto> missions, List<VipRedemptionDto> vipCatalog, List<RewardTransactionDto> history) {}
record RewardsOperationRequest(String operationKey) {}
record RewardsOperationResultDto(boolean accepted, Long bonusBalance, Long vipPointsBalance, String subscriptionExpiresAt, RewardsOverviewDto overview) {}

@RestController
@RequestMapping("/v1/rewards")
class RewardsController {
    private final RewardsService rewards;

    RewardsController(RewardsService rewards) { this.rewards = rewards; }

    @GetMapping("/overview")
    RewardsOverviewDto overview(Authentication authentication) {
        return rewards.overview(userId(authentication));
    }

    @PostMapping("/check-in")
    RewardsOperationResultDto checkIn(Authentication authentication, @RequestBody RewardsOperationRequest request) {
        return rewards.checkIn(userId(authentication), operationKey(request));
    }

    @PostMapping("/missions/{missionId}/claim")
    RewardsOperationResultDto claimMission(Authentication authentication, @PathVariable String missionId, @RequestBody RewardsOperationRequest request) {
        return rewards.claimMission(userId(authentication), missionId, operationKey(request));
    }

    @PostMapping("/vip/{optionId}/redeem")
    RewardsOperationResultDto redeemVip(Authentication authentication, @PathVariable String optionId, @RequestBody RewardsOperationRequest request) {
        return rewards.redeemVip(userId(authentication), optionId, operationKey(request));
    }

    private static UUID userId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try { return UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
    }

    private static String operationKey(RewardsOperationRequest request) {
        if (request == null || request.operationKey() == null || request.operationKey().isBlank() || request.operationKey().length() > 160) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "operationKey is required");
        }
        return request.operationKey().trim();
    }
}

@Service
class RewardsService {
    private static final long[] CHECK_IN_REWARDS = {30, 30, 50, 50, 80, 100, 150};

    private final JdbcTemplate jdbc;
    private final ZoneId rewardsZone;

    RewardsService(JdbcTemplate jdbc, @Value("${rewards.zone-id:America/Sao_Paulo}") String zoneId) {
        this.jdbc = jdbc;
        this.rewardsZone = ZoneId.of(zoneId);
    }

    RewardsOverviewDto overview(UUID userId) {
        var today = LocalDate.now(rewardsZone);
        return new RewardsOverviewDto(
            balance(userId, "BONUS"),
            balance(userId, "VIP_POINTS"),
            checkInSnapshot(userId, today),
            missions(userId),
            vipCatalog(),
            history(userId)
        );
    }

    @Transactional
    RewardsOperationResultDto checkIn(UUID userId, String operationKey) {
        lock(userId);
        var today = LocalDate.now(rewardsZone);

        var existingOperation = jdbc.queryForObject(
            "select count(*) from reward_ledger where user_id=? and ledger_type='BONUS' and operation_key=?",
            Integer.class, userId, operationKey
        );
        if (existingOperation != null && existingOperation > 0) return result(true, userId);

        var alreadyToday = jdbc.queryForObject(
            "select count(*) from daily_check_in where user_id=? and check_in_date=?",
            Integer.class, userId, Date.valueOf(today)
        );
        if (alreadyToday != null && alreadyToday > 0) return result(false, userId);

        var previous = jdbc.query(
            "select check_in_date, streak_day from daily_check_in where user_id=? order by check_in_date desc limit 1",
            (rs, rowNum) -> new PreviousCheckIn(rs.getDate(1).toLocalDate(), rs.getInt(2)), userId
        ).stream().findFirst().orElse(null);

        int streakDay = previous != null && previous.date().equals(today.minusDays(1))
            ? (previous.streakDay() % CHECK_IN_REWARDS.length) + 1
            : 1;
        long reward = CHECK_IN_REWARDS[streakDay - 1];

        jdbc.update(
            "insert into reward_ledger(id,user_id,ledger_type,operation_key,amount,reference_type,reference_id,created_at) values (?,?,?,?,?,?,?,now())",
            UUID.randomUUID(), userId, "BONUS", operationKey, reward, "CHECK_IN", today.toString()
        );
        jdbc.update(
            "insert into daily_check_in(user_id,check_in_date,streak_day,reward_amount,operation_key,created_at) values (?,?,?,?,?,now())",
            userId, Date.valueOf(today), streakDay, reward, operationKey
        );
        return result(true, userId);
    }

    @Transactional
    RewardsOperationResultDto claimMission(UUID userId, String missionId, String operationKey) {
        if (missionId == null || missionId.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "missionId is required");
        lock(userId);

        var prior = jdbc.queryForObject(
            "select count(*) from reward_ledger where user_id=? and operation_key=?",
            Integer.class, userId, operationKey
        );
        if (prior != null && prior > 0) return result(true, userId);

        var mission = jdbc.query(
            "select m.reward_type,m.reward_amount,um.status from reward_mission m join user_mission um on um.mission_id=m.id and um.user_id=? where m.id=? and m.enabled=true",
            (rs, rowNum) -> new MissionClaim(rs.getString(1), rs.getLong(2), rs.getString(3)), userId, missionId
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "Mission is not claimable"));

        if ("CLAIMED".equals(mission.status())) return result(false, userId);
        if (!"COMPLETED".equals(mission.status())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Mission is not completed");
        if (!Set.of("BONUS", "VIP_POINTS").contains(mission.rewardType())) throw new ResponseStatusException(HttpStatus.CONFLICT, "Unsupported reward type");

        jdbc.update(
            "insert into reward_ledger(id,user_id,ledger_type,operation_key,amount,reference_type,reference_id,created_at) values (?,?,?,?,?,?,?,now())",
            UUID.randomUUID(), userId, mission.rewardType(), operationKey, mission.amount(), "MISSION", missionId
        );
        jdbc.update(
            "update user_mission set status='CLAIMED', claimed_operation_key=?, updated_at=now() where user_id=? and mission_id=? and status='COMPLETED'",
            operationKey, userId, missionId
        );
        return result(true, userId);
    }

    @Transactional
    RewardsOperationResultDto redeemVip(UUID userId, String optionId, String operationKey) {
        if (optionId == null || optionId.isBlank() || optionId.length() > 80) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "optionId is required");
        }
        lock(userId);

        var existing = jdbc.query(
            "select expires_at from vip_redemption where user_id=? and operation_key=?",
            (rs, row) -> rs.getObject(1, OffsetDateTime.class),
            userId,
            operationKey
        ).stream().findFirst().orElse(null);
        if (existing != null) return result(true, userId, existing);

        var option = jdbc.query(
            "select required_vip_points,vip_days from vip_redemption_option where id=? and enabled=true",
            (rs, row) -> new VipOption(rs.getLong(1), rs.getInt(2)),
            optionId
        ).stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "VIP option not found"));

        long available = balance(userId, "VIP_POINTS");
        if (available < option.requiredPoints()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Insufficient VIP points");
        }

        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime currentExpiry = activeVipExpiry(userId);
        OffsetDateTime startsAt = currentExpiry != null && currentExpiry.isAfter(now) ? currentExpiry : now;
        OffsetDateTime expiresAt = startsAt.plusDays(option.vipDays());

        jdbc.update(
            "insert into reward_ledger(id,user_id,ledger_type,operation_key,amount,reference_type,reference_id,created_at) values (?,?,?,?,?,?,?,now())",
            UUID.randomUUID(), userId, "VIP_POINTS", operationKey, -option.requiredPoints(), "VIP_REDEMPTION", optionId
        );
        jdbc.update(
            """
            insert into vip_redemption(id,user_id,option_id,operation_key,points_spent,vip_days,starts_at,expires_at,created_at)
            values (?,?,?,?,?,?,?,?,now())
            """,
            UUID.randomUUID(), userId, optionId, operationKey, option.requiredPoints(), option.vipDays(), startsAt, expiresAt
        );
        return result(true, userId, expiresAt);
    }

    private RewardsOperationResultDto result(boolean accepted, UUID userId) {
        return result(accepted, userId, activeVipExpiry(userId));
    }

    private RewardsOperationResultDto result(boolean accepted, UUID userId, OffsetDateTime expiry) {
        var overview = overview(userId);
        return new RewardsOperationResultDto(
            accepted,
            overview.bonusBalance(),
            overview.vipPointsBalance(),
            expiry == null ? null : expiry.toString(),
            overview
        );
    }

    private long balance(UUID userId, String ledgerType) {
        var value = jdbc.queryForObject(
            "select coalesce(sum(amount),0) from reward_ledger where user_id=? and ledger_type=?",
            Long.class, userId, ledgerType
        );
        return value == null ? 0L : value;
    }

    private RewardsCheckInDto checkInSnapshot(UUID userId, LocalDate today) {
        var recent = jdbc.query(
            "select check_in_date,streak_day from daily_check_in where user_id=? order by check_in_date desc limit 1",
            (rs, rowNum) -> new PreviousCheckIn(rs.getDate(1).toLocalDate(), rs.getInt(2)), userId
        ).stream().findFirst().orElse(null);

        boolean claimedToday = recent != null && recent.date().equals(today);
        int currentDay;
        int streakDays;
        if (claimedToday) {
            currentDay = recent.streakDay();
            streakDays = recent.streakDay();
        } else if (recent != null && recent.date().equals(today.minusDays(1))) {
            currentDay = (recent.streakDay() % CHECK_IN_REWARDS.length) + 1;
            streakDays = recent.streakDay();
        } else {
            currentDay = 1;
            streakDays = 0;
        }

        var days = new ArrayList<RewardsCheckInDayDto>();
        for (int i = 1; i <= CHECK_IN_REWARDS.length; i++) {
            boolean claimed = claimedToday ? i <= currentDay : i < currentDay && streakDays > 0;
            days.add(new RewardsCheckInDayDto(i, CHECK_IN_REWARDS[i - 1], claimed));
        }
        return new RewardsCheckInDto(streakDays, currentDay, claimedToday, !claimedToday, days);
    }

    private List<RewardMissionDto> missions(UUID userId) {
        return jdbc.query(
            """
            select m.id,m.title,m.description,m.reward_type,m.reward_amount,coalesce(um.progress,0),m.target,
                   case when um.status is null then 'IN_PROGRESS' else um.status end,m.action_url
              from reward_mission m
              left join user_mission um on um.mission_id=m.id and um.user_id=?
             where m.enabled=true
             order by m.id
            """,
            (rs, rowNum) -> new RewardMissionDto(
                rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                rs.getLong(6), rs.getObject(7) == null ? null : rs.getLong(7), rs.getString(8), rs.getString(9)
            ), userId
        );
    }

    private List<VipRedemptionDto> vipCatalog() {
        return jdbc.query(
            """
            select id,label,required_vip_points,vip_days,enabled
            from vip_redemption_option
            where enabled=true
            order by display_order,id
            """,
            (rs, row) -> new VipRedemptionDto(
                rs.getString("id"),
                rs.getString("label"),
                rs.getLong("required_vip_points"),
                rs.getInt("vip_days"),
                rs.getBoolean("enabled")
            )
        );
    }

    private List<RewardTransactionDto> history(UUID userId) {
        return jdbc.query("""
            select id,ledger_type,amount,reference_type,reference_id,created_at
              from reward_ledger
             where user_id=?
             order by created_at desc,id desc
             limit 20
            """,
            (rs, row) -> new RewardTransactionDto(
                rs.getObject("id").toString(),
                rs.getString("ledger_type"),
                rs.getLong("amount"),
                rs.getString("reference_type"),
                rs.getString("reference_id"),
                rs.getObject("created_at", OffsetDateTime.class).toString()
            ),
            userId
        );
    }

    private OffsetDateTime activeVipExpiry(UUID userId) {
        return jdbc.query(
            "select expires_at from vip_redemption where user_id=? and expires_at>now() order by expires_at desc limit 1",
            (rs, row) -> rs.getObject(1, OffsetDateTime.class),
            userId
        ).stream().findFirst().orElse(null);
    }

    private void lock(UUID userId) {
        jdbc.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtext(?))) rewards_lock", Integer.class, userId.toString());
    }

    private record PreviousCheckIn(LocalDate date, int streakDay) {}
    private record MissionClaim(String rewardType, long amount, String status) {}
    private record VipOption(long requiredPoints, int vipDays) {}
}
