package br.com.brasildrama.analytics;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.liquibase.contexts=base,dev",
    "security.jwt.secret=test-secret-for-brasil-drama-must-have-32-bytes"
})
class AdminAnalyticsContractTest {
    @Autowired AdminAnalyticsApi analytics;

    @Test
    void returnsSafeAggregatesForSupportedPeriods() {
        var result = analytics.catalog(7);

        assertThat(result.days()).isEqualTo(7);
        assertThat(result.summary().plays()).isGreaterThanOrEqualTo(0);
        assertThat(result.summary().viewers()).isGreaterThanOrEqualTo(0);
        assertThat(result.summary().averageCompletionPercent()).isBetween(0, 100);
        assertThat(result.retention().started()).isGreaterThanOrEqualTo(0);
        assertThat(result.retention().reached25()).isGreaterThanOrEqualTo(0);
        assertThat(result.retention().reached50()).isGreaterThanOrEqualTo(0);
        assertThat(result.retention().reached75()).isGreaterThanOrEqualTo(0);
        assertThat(result.retention().completed()).isGreaterThanOrEqualTo(0);
        assertThat(result.errors()).isNotNull();
        assertThat(result.dramas()).isNotNull();
        assertThat(result.episodes()).isNotNull();
    }

    @Test
    void fallsBackToThirtyDaysForUnsupportedPeriod() {
        assertThat(analytics.catalog(13).days()).isEqualTo(30);
    }
}
