package br.com.brasildrama.admin;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.liquibase.contexts=base,dev",
    "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes"
})
class AdminDashboardContractTest {
    @Autowired AdminDashboardApi dashboard;

    @Test
    void aggregatesOperationalMetricsFromMigratedDatabase() {
        var result = dashboard.dashboard();

        assertThat(result).isNotNull();
        assertThat(result.metrics().publishedDramas()).isGreaterThanOrEqualTo(0);
        assertThat(result.metrics().availableEpisodes()).isGreaterThanOrEqualTo(0);
        assertThat(result.metrics().registeredUsers()).isGreaterThanOrEqualTo(0);
        assertThat(result.metrics().validatedPurchases()).isGreaterThanOrEqualTo(0);
        assertThat(result.performance().plays30d()).isGreaterThanOrEqualTo(0);
        assertThat(result.performance().viewers30d()).isGreaterThanOrEqualTo(0);
        assertThat(result.performance().completedEpisodes30d()).isGreaterThanOrEqualTo(0);
        assertThat(result.performance().averageCompletionPercent30d()).isBetween(0, 100);
        assertThat(result.catalog().draft()).isGreaterThanOrEqualTo(0);
        assertThat(result.catalog().published()).isGreaterThanOrEqualTo(0);
        assertThat(result.attention()).isNotNull();
    }
}
