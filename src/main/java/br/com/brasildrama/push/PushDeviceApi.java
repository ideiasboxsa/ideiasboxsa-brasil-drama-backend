package br.com.brasildrama.push;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

record PushDeviceRequest(String token, String platform, Boolean marketingEnabled, Boolean newEpisodeEnabled, Boolean rewardEnabled) {}
record PushDeviceDto(UUID id, String platform, String provider, boolean enabled, boolean marketingEnabled, boolean newEpisodeEnabled, boolean rewardEnabled) {}
record PushDeviceStats(long devices, long enabledDevices, long marketingEnabled, long newEpisodeEnabled, long rewardEnabled) {}

@RestController
class PushDeviceController {
    private final PushDeviceService service;

    PushDeviceController(PushDeviceService service) { this.service = service; }

    @PutMapping("/v1/me/push/device")
    PushDeviceDto register(Authentication auth, @RequestBody PushDeviceRequest request) {
        return service.register(userId(auth), request);
    }

    @DeleteMapping("/v1/me/push/device")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void disable(Authentication auth, @RequestParam String token) {
        service.disable(userId(auth), token);
    }

    @GetMapping("/v1/admin/analytics/push-devices")
    PushDeviceStats stats() { return service.stats(); }

    private static UUID userId(Authentication auth) {
        if (auth == null || auth.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try { return UUID.fromString(auth.getName()); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
    }
}

@Service
class PushDeviceService {
    private final JdbcTemplate jdbc;

    PushDeviceService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional
    PushDeviceDto register(UUID userId, PushDeviceRequest request) {
        if (request == null || request.token() == null || request.token().isBlank() || request.token().length() > 512) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "push token is required");
        }
        var platform = request.platform() == null ? "ANDROID" : request.platform().trim().toUpperCase();
        if (!List.of("ANDROID", "IOS", "WEB").contains(platform)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid platform");
        boolean marketing = request.marketingEnabled() == null || request.marketingEnabled();
        boolean episodes = request.newEpisodeEnabled() == null || request.newEpisodeEnabled();
        boolean rewards = request.rewardEnabled() == null || request.rewardEnabled();
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into push_device(id,user_id,platform,provider,token,enabled,marketing_enabled,new_episode_enabled,reward_enabled,last_seen_at,created_at,updated_at)
            values (?,?,?,'FCM',?,true,?,?,?,now(),now(),now())
            on conflict (token) do update set user_id=excluded.user_id, platform=excluded.platform, enabled=true,
              marketing_enabled=excluded.marketing_enabled, new_episode_enabled=excluded.new_episode_enabled,
              reward_enabled=excluded.reward_enabled, last_seen_at=now(), updated_at=now()
            """, id, userId, platform, request.token().trim(), marketing, episodes, rewards);
        return jdbc.query("select id,platform,provider,enabled,marketing_enabled,new_episode_enabled,reward_enabled from push_device where token=?",
            (rs, n) -> new PushDeviceDto(rs.getObject("id", UUID.class), rs.getString("platform"), rs.getString("provider"), rs.getBoolean("enabled"), rs.getBoolean("marketing_enabled"), rs.getBoolean("new_episode_enabled"), rs.getBoolean("reward_enabled")), request.token().trim()).stream().findFirst().orElseThrow();
    }

    @Transactional
    void disable(UUID userId, String token) {
        if (token == null || token.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "push token is required");
        jdbc.update("update push_device set enabled=false,updated_at=now() where user_id=? and token=?", userId, token.trim());
    }

    PushDeviceStats stats() {
        return jdbc.queryForObject("""
            select count(*) devices,
                   count(*) filter (where enabled) enabled_devices,
                   count(*) filter (where enabled and marketing_enabled) marketing_enabled,
                   count(*) filter (where enabled and new_episode_enabled) new_episode_enabled,
                   count(*) filter (where enabled and reward_enabled) reward_enabled
            from push_device
            """, (rs, n) -> new PushDeviceStats(rs.getLong("devices"), rs.getLong("enabled_devices"), rs.getLong("marketing_enabled"), rs.getLong("new_episode_enabled"), rs.getLong("reward_enabled")));
    }
}
