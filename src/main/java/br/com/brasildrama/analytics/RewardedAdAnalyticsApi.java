package br.com.brasildrama.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

record RewardedAdAnalyticsResponse(
    int days,
    long sessionsCreated,
    long sessionsVerified,
    long sessionsClaimed,
    long uniqueRewardedUsers,
    long bonusGranted,
    double verificationRatePercent,
    double claimRatePercent
) {}

@RestController
class RewardedAdAnalyticsApi {
    private final JdbcTemplate jdbc;

    RewardedAdAnalyticsApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/admin/analytics/rewarded-ads")
    RewardedAdAnalyticsResponse rewardedAds(@RequestParam(defaultValue = "7") int days) {
        int normalizedDays = switch (days) {
            case 30 -> 30;
            case 90 -> 90;
            default -> 7;
        };

        var funnel = jdbc.queryForMap("""
            select count(*) sessions_created,
                   count(*) filter (where verified_at is not null) sessions_verified,
                   count(*) filter (where claimed_at is not null) sessions_claimed,
                   count(distinct user_id) filter (where claimed_at is not null) unique_rewarded_users
              from rewarded_ad_session
             where created_at >= now() - (? * interval '1 day')
            """, normalizedDays);

        Long bonusGranted = jdbc.queryForObject("""
            select coalesce(sum(amount), 0)
              from reward_ledger
             where ledger_type = 'BONUS'
               and reference_type = 'REWARDED_AD'
               and created_at >= now() - (? * interval '1 day')
            """, Long.class, normalizedDays);

        long created = value(funnel, "sessions_created");
        long verified = value(funnel, "sessions_verified");
        long claimed = value(funnel, "sessions_claimed");

        return new RewardedAdAnalyticsResponse(
            normalizedDays,
            created,
            verified,
            claimed,
            value(funnel, "unique_rewarded_users"),
            bonusGranted == null ? 0 : bonusGranted,
            percentage(verified, created),
            percentage(claimed, verified)
        );
    }

    private static long value(java.util.Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static double percentage(long numerator, long denominator) {
        if (denominator <= 0) return 0.0;
        return Math.round((numerator * 10000.0 / denominator)) / 100.0;
    }
}
