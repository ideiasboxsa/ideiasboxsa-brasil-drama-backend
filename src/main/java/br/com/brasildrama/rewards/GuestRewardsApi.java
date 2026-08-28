package br.com.brasildrama.rewards;

import br.com.brasildrama.identity.VisitorIdentity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/v1/rewards")
class GuestRewardsApi {
    private final JdbcTemplate jdbc;
    private final long loginRewardAmount;

    GuestRewardsApi(
        JdbcTemplate jdbc,
        @Value("${rewards.welcome.bonus:100}") long loginRewardAmount
    ) {
        this.jdbc = jdbc;
        this.loginRewardAmount = Math.max(0, loginRewardAmount);
    }

    @GetMapping("/guest-overview")
    GuestRewardsOverviewDto guestOverview(@RequestHeader(VisitorIdentity.HEADER) String rawVisitorId) {
        var visitor = VisitorIdentity.parse(rawVisitorId);
        touch(visitor);

        var vipCatalog = jdbc.query(
            "select id,label,required_vip_points,vip_days,enabled from vip_redemption_option where enabled=true order by required_vip_points asc",
            (rs, row) -> new GuestVipRedemptionDto(
                rs.getString("id"),
                rs.getString("label"),
                rs.getLong("required_vip_points"),
                rs.getInt("vip_days"),
                rs.getBoolean("enabled")
            )
        );

        return new GuestRewardsOverviewDto(
            visitor.externalId(),
            0L,
            0L,
            loginRewardAmount,
            false,
            vipCatalog,
            List.of(
                new GuestRewardMissionDto(
                    "LOGIN",
                    "Entre na sua conta",
                    "Sincronize seus benefícios e ganhe sua recompensa de boas-vindas.",
                    "BONUS",
                    loginRewardAmount,
                    "brasildrama://account"
                )
            )
        );
    }

    private void touch(VisitorIdentity visitor) {
        jdbc.update(
            """
            insert into visitor_identity(visitor_id,first_seen_at,last_seen_at)
            values (?,now(),now())
            on conflict (visitor_id) do update set last_seen_at=excluded.last_seen_at
            """,
            visitor.id()
        );
    }

    record GuestRewardsOverviewDto(
        String visitorId,
        long bonusBalance,
        long vipPointsBalance,
        long loginRewardAmount,
        boolean authenticated,
        List<GuestVipRedemptionDto> vipCatalog,
        List<GuestRewardMissionDto> missions
    ) {}

    record GuestVipRedemptionDto(
        String id,
        String label,
        long requiredVipPoints,
        int vipDays,
        boolean enabled
    ) {}

    record GuestRewardMissionDto(
        String id,
        String title,
        String description,
        String rewardType,
        long rewardAmount,
        String actionUrl
    ) {}
}
