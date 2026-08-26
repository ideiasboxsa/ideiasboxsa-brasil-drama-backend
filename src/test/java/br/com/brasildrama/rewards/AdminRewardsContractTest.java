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
    }
}
