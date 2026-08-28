package br.com.brasildrama.rewards;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RewardGrantServiceContractTest {
    @Test
    void welcomeBonusIsGrantedOnlyWhenLedgerInsertSucceeds() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1, 0);
        var service = new RewardGrantService(jdbc, true, 100);
        var userId = UUID.randomUUID();

        assertThat(service.grantWelcomeBonus(userId)).isEqualTo(100L);
        assertThat(service.grantWelcomeBonus(userId)).isZero();
    }

    @Test
    void disabledWelcomeBonusNeverTouchesLedger() {
        var jdbc = mock(JdbcTemplate.class);
        var service = new RewardGrantService(jdbc, false, 100);

        assertThat(service.grantWelcomeBonus(UUID.randomUUID())).isZero();
    }
}
