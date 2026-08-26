package br.com.brasildrama.rewards;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class VipAccessService {
    private final JdbcTemplate jdbc;

    public VipAccessService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OffsetDateTime> activeUntil(UUID userId) {
        return jdbc.query(
            "select expires_at from vip_redemption where user_id=? and expires_at>now() order by expires_at desc limit 1",
            (rs, row) -> rs.getObject(1, OffsetDateTime.class),
            userId
        ).stream().findFirst();
    }
}
