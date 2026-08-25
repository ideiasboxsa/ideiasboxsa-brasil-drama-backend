package br.com.brasildrama.rewards;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class RewardGrantService {
    private final JdbcTemplate jdbc;
    private final boolean welcomeEnabled;
    private final long welcomeBonus;

    public RewardGrantService(
        JdbcTemplate jdbc,
        @Value("${rewards.welcome.enabled:false}") boolean welcomeEnabled,
        @Value("${rewards.welcome.bonus:50}") long welcomeBonus
    ) {
        this.jdbc = jdbc;
        this.welcomeEnabled = welcomeEnabled;
        this.welcomeBonus = Math.max(0, welcomeBonus);
    }

    @Transactional
    public void grantWelcomeBonus(UUID userId) {
        if (!welcomeEnabled || welcomeBonus <= 0) return;
        var operationKey = "welcome:" + userId;
        jdbc.update(
            """
            insert into reward_ledger(id,user_id,ledger_type,operation_key,amount,reference_type,reference_id,created_at)
            values (?,?,?,?,?,?,?,now())
            on conflict (user_id,operation_key) do nothing
            """,
            UUID.randomUUID(), userId, "BONUS", operationKey, welcomeBonus, "WELCOME", userId.toString()
        );
    }
}
