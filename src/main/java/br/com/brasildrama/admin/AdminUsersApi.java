package br.com.brasildrama.admin;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

record AdminUserSummary(UUID id, String email, String displayName, OffsetDateTime createdAt, int coins, long bonus, long vipPoints, OffsetDateTime vipUntil) {}
record AdminUsersPage(List<AdminUserSummary> items, long total, int limit, int offset) {}
record AdminUserNoteRequest(String note) {}
record AdminUserNoteDto(UUID id, String note, UUID operatorId, String operatorName, OffsetDateTime createdAt) {}
record AdminUserDetail(
    UUID id, String email, String displayName, OffsetDateTime createdAt,
    int coins, long bonus, long vipPoints, OffsetDateTime vipUntil,
    long favorites, long watching, long unlockedEpisodes, long purchases,
    long missionsCompleted, long missionsClaimed, List<AdminUserNoteDto> notes
) {}

@RestController
public class AdminUsersApi {
    private final JdbcTemplate jdbc;

    AdminUsersApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/v1/admin/users")
    AdminUsersPage list(
        @RequestParam(defaultValue = "") String q,
        @RequestParam(defaultValue = "20") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        int safeOffset = Math.max(0, offset);
        String term = "%" + q.trim().toLowerCase() + "%";
        long total = value(jdbc.queryForObject("""
            select count(*) from app_user u
            where ? = '%%'
               or lower(u.email) like ?
               or lower(coalesce(u.display_name, '')) like ?
               or lower(u.id::text) like ?
            """, Long.class, term, term, term, term));

        List<AdminUserSummary> items = jdbc.query("""
            select u.id, u.email, u.display_name, u.created_at,
                   coalesce((select sum(w.amount) from wallet_ledger w where w.user_id = u.id), 0) coins,
                   coalesce((select sum(r.amount) from reward_ledger r where r.user_id = u.id and r.ledger_type = 'BONUS'), 0) bonus,
                   coalesce((select sum(r.amount) from reward_ledger r where r.user_id = u.id and r.ledger_type = 'VIP_POINTS'), 0) vip_points,
                   greatest(
                     (select max(v.expires_at) from vip_redemption v where v.user_id = u.id and v.expires_at > now()),
                     (select max(p.expires_at) from google_play_purchase p where p.user_id = u.id and p.product_type = 'SUBSCRIPTION' and p.expires_at > now())
                   ) vip_until
            from app_user u
            where ? = '%%'
               or lower(u.email) like ?
               or lower(coalesce(u.display_name, '')) like ?
               or lower(u.id::text) like ?
            order by u.created_at desc, u.id
            limit ? offset ?
            """, this::summary, term, term, term, term, safeLimit, safeOffset);
        return new AdminUsersPage(items, total, safeLimit, safeOffset);
    }

    @GetMapping("/v1/admin/users/{userId}")
    AdminUserDetail detail(@PathVariable UUID userId) {
        List<AdminUserDetail> rows = jdbc.query("""
            select u.id, u.email, u.display_name, u.created_at,
                   coalesce((select sum(w.amount) from wallet_ledger w where w.user_id = u.id), 0) coins,
                   coalesce((select sum(r.amount) from reward_ledger r where r.user_id = u.id and r.ledger_type = 'BONUS'), 0) bonus,
                   coalesce((select sum(r.amount) from reward_ledger r where r.user_id = u.id and r.ledger_type = 'VIP_POINTS'), 0) vip_points,
                   greatest(
                     (select max(v.expires_at) from vip_redemption v where v.user_id = u.id and v.expires_at > now()),
                     (select max(p.expires_at) from google_play_purchase p where p.user_id = u.id and p.product_type = 'SUBSCRIPTION' and p.expires_at > now())
                   ) vip_until,
                   (select count(*) from user_favorite f where f.user_id = u.id) favorites,
                   (select count(*) from playback_history h where h.user_id = u.id) watching,
                   (select count(*) from episode_entitlement e where e.user_id = u.id) unlocked_episodes,
                   (select count(*) from google_play_purchase p where p.user_id = u.id) purchases,
                   (select count(*) from user_mission m where m.user_id = u.id and m.status in ('COMPLETED','CLAIMED')) missions_completed,
                   (select count(*) from user_mission m where m.user_id = u.id and m.status = 'CLAIMED') missions_claimed
            from app_user u where u.id = ?
            """, (rs, row) -> new AdminUserDetail(
                rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getInt("coins"), rs.getLong("bonus"),
                rs.getLong("vip_points"), rs.getObject("vip_until", OffsetDateTime.class),
                rs.getLong("favorites"), rs.getLong("watching"), rs.getLong("unlocked_episodes"),
                rs.getLong("purchases"), rs.getLong("missions_completed"), rs.getLong("missions_claimed"), notes(userId)
            ), userId);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        return rows.getFirst();
    }

    @PostMapping("/v1/admin/users/{userId}/notes")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    AdminUserNoteDto addNote(Authentication authentication, @PathVariable UUID userId, @RequestBody AdminUserNoteRequest request) {
        String note = request == null || request.note() == null ? "" : request.note().trim();
        if (note.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "NOTE_REQUIRED");
        if (note.length() > 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "NOTE_TOO_LONG");
        Integer userExists = jdbc.queryForObject("select count(*) from app_user where id=?", Integer.class, userId);
        if (userExists == null || userExists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND");
        UUID id = UUID.randomUUID();
        jdbc.update("insert into admin_user_note(id,user_id,operator_id,note,created_at) values(?,?,?,?,now())",
            id, userId, operatorId(authentication), note);
        return notes(userId).stream().filter(item -> item.id().equals(id)).findFirst().orElseThrow();
    }

    private List<AdminUserNoteDto> notes(UUID userId) {
        return jdbc.query("""
            select n.id,n.note,n.operator_id,a.display_name as operator_name,n.created_at
            from admin_user_note n join admin_operator a on a.id=n.operator_id
            where n.user_id=? order by n.created_at desc,n.id desc limit 50
            """, (rs, row) -> new AdminUserNoteDto(
                rs.getObject("id", UUID.class), rs.getString("note"),
                rs.getObject("operator_id", UUID.class), rs.getString("operator_name"),
                rs.getObject("created_at", OffsetDateTime.class)
            ), userId);
    }

    private static UUID operatorId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        try { return UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
    }

    private AdminUserSummary summary(ResultSet rs, int row) throws SQLException {
        return new AdminUserSummary(
            rs.getObject("id", UUID.class), rs.getString("email"), rs.getString("display_name"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getInt("coins"), rs.getLong("bonus"),
            rs.getLong("vip_points"), rs.getObject("vip_until", OffsetDateTime.class)
        );
    }

    private static long value(Long value) {
        return value == null ? 0 : value;
    }
}
