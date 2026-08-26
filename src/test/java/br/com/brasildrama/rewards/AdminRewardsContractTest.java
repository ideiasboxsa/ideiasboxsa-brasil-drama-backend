package br.com.brasildrama.rewards;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.liquibase.contexts=base,dev",
    "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes"
})
class AdminRewardsContractTest {
    @Autowired AdminRewardsApi rewards;

    @Test
    void aggregatesRewardEconomyFromMigratedDatabase() {
        var result = rewards.overview();

        assertThat(result).isNotNull();
        assertThat(result.summary().usersWithRewards()).isGreaterThanOrEqualTo(0);
        assertThat(result.summary().checkInsToday()).isGreaterThanOrEqualTo(0);
        assertThat(result.summary().checkIns30d()).isGreaterThanOrEqualTo(result.summary().checkInsToday());
        assertThat(result.rewardedAds().created()).isGreaterThanOrEqualTo(0);
        assertThat(result.rewardedAds().claimed()).isGreaterThanOrEqualTo(0);
        assertThat(result.missions()).isNotNull();
        assertThat(result.vipOptions()).isNotNull();
        assertThat(result.recentTransactions()).isNotNull();
        assertThat(result.checkInCycle()).isNotEmpty();
        assertThat(result.fraudSignals()).isNotNull();
        assertThat(result.fraudSignals().unverifiedAdClaims()).isZero();
    }

    @Test
    void updatesCheckInCycleAtomically() {
        var updated = rewards.updateCheckInCycle(new AdminCheckInCycleUpdate(java.util.List.of(
            new AdminCheckInDay(1, 25),
            new AdminCheckInDay(2, 50),
            new AdminCheckInDay(3, 100)
        )));

        assertThat(updated).extracting(AdminCheckInDay::rewardAmount).containsExactly(25L, 50L, 100L);
        assertThat(rewards.overview().checkInCycle()).hasSize(3);

        rewards.updateCheckInCycle(new AdminCheckInCycleUpdate(java.util.List.of(
            new AdminCheckInDay(1, 30), new AdminCheckInDay(2, 30), new AdminCheckInDay(3, 50),
            new AdminCheckInDay(4, 50), new AdminCheckInDay(5, 80), new AdminCheckInDay(6, 100),
            new AdminCheckInDay(7, 150)
        )));
    }

    @Test
    void createsAndEditsMissionCatalog() {
        var created = rewards.createMission(new AdminRewardMissionWrite(
            "contract-mission",
            "Missão contratual",
            "Assista dois episódios para concluir.",
            "BONUS",
            40,
            2L,
            "EPISODE_COMPLETED",
            "brasildrama://discover",
            null,
            null,
            false
        ));

        assertThat(created.id()).isEqualTo("contract-mission");
        assertThat(created.enabled()).isFalse();
        assertThat(created.actionUrl()).isEqualTo("brasildrama://discover");

        var updated = rewards.updateMissionDetails("contract-mission", new AdminRewardMissionWrite(
            "contract-mission",
            "Missão atualizada",
            "Assista dois episódios e ganhe pontos VIP.",
            "VIP_POINTS",
            20,
            2L,
            "EPISODE_COMPLETED",
            "brasildrama://home",
            "2026-01-01T00:00:00Z",
            "2027-01-01T00:00:00Z",
            true
        ));

        assertThat(updated.title()).isEqualTo("Missão atualizada");
        assertThat(updated.rewardType()).isEqualTo("VIP_POINTS");
        assertThat(updated.actionUrl()).isEqualTo("brasildrama://home");
        assertThat(updated.startsAt()).isEqualTo("2026-01-01T00:00Z");
        assertThat(updated.endsAt()).isEqualTo("2027-01-01T00:00Z");
        assertThat(updated.enabled()).isTrue();
    }
}
