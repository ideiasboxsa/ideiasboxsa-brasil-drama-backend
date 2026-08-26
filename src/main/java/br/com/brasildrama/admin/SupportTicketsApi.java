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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

record SupportTicketCreateRequest(String category, String subject, String message) {}
record SupportTicketUpdateRequest(String status, String adminNote) {}
record SupportTicketDto(
    UUID id, String code, UUID userId, String userEmail, String userDisplayName,
    String category, String subject, String message, String status, String adminNote,
    OffsetDateTime createdAt, OffsetDateTime updatedAt
) {}

@RestController
class SupportTicketsApi {
    private static final Set<String> CATEGORIES = Set.of("ACCOUNT", "PAYMENT", "PLAYBACK", "REWARDS", "OTHER");
    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");
    private final JdbcTemplate jdbc;

    SupportTicketsApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostMapping("/v1/me/support-tickets")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    SupportTicketDto create(Authentication authentication, @RequestBody SupportTicketCreateRequest request) {
        UUID userId = userId(authentication);
        String category = normalized(request == null ? null : request.category(), 40, "category");
        String subject = normalized(request == null ? null : request.subject(), 160, "subject");
        String message = normalized(request == null ? null : request.message(), 2000, "message");
        if (!CATEGORIES.contains(category)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CATEGORY");
        UUID id = UUID.randomUUID();
        jdbc.update("""
            insert into support_ticket(id,user_id,category,subject,message,status,created_at,updated_at)
            values(?,?,?,?,?,'OPEN',now(),now())
            """, id, userId, category, subject, message);
        return find(id);
    }

    @GetMapping("/v1/me/support-tickets")
    List<SupportTicketDto> mine(Authentication authentication) {
        return jdbc.query("""
            select t.*,u.email,u.display_name from support_ticket t
            join app_user u on u.id=t.user_id
            where t.user_id=? order by t.created_at desc limit 20
            """, this::map, userId(authentication));
    }

    @GetMapping("/v1/admin/support-tickets")
    List<SupportTicketDto> adminList(@RequestParam(required = false) String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query("""
                select t.*,u.email,u.display_name from support_ticket t
                join app_user u on u.id=t.user_id order by
                case t.status when 'OPEN' then 0 when 'IN_PROGRESS' then 1 when 'RESOLVED' then 2 else 3 end,
                t.created_at desc limit 200
                """, this::map);
        }
        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalizedStatus)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        return jdbc.query("""
            select t.*,u.email,u.display_name from support_ticket t
            join app_user u on u.id=t.user_id where t.status=? order by t.created_at desc limit 200
            """, this::map, normalizedStatus);
    }

    @PutMapping("/v1/admin/support-tickets/{ticketId}")
    @Transactional
    SupportTicketDto update(@PathVariable UUID ticketId, @RequestBody SupportTicketUpdateRequest request) {
        String status = normalized(request == null ? null : request.status(), 24, "status");
        if (!STATUSES.contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        String note = request == null || request.adminNote() == null ? null : request.adminNote().trim();
        if (note != null && note.length() > 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADMIN_NOTE_TOO_LONG");
        int changed = jdbc.update("update support_ticket set status=?,admin_note=?,updated_at=now() where id=?", status, note, ticketId);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
        return find(ticketId);
    }

    private SupportTicketDto find(UUID id) {
        List<SupportTicketDto> rows = jdbc.query("""
            select t.*,u.email,u.display_name from support_ticket t
            join app_user u on u.id=t.user_id where t.id=?
            """, this::map, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
        return rows.getFirst();
    }

    private SupportTicketDto map(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        return new SupportTicketDto(
            id, "BD-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT),
            rs.getObject("user_id", UUID.class), rs.getString("email"), rs.getString("display_name"),
            rs.getString("category"), rs.getString("subject"), rs.getString("message"),
            rs.getString("status"), rs.getString("admin_note"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    private static UUID userId(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try { return UUID.fromString(authentication.getName()); }
        catch (IllegalArgumentException ex) { throw new ResponseStatusException(HttpStatus.UNAUTHORIZED); }
    }

    private static String normalized(String value, int max, String field) {
        if (value == null || value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        String normalized = value.trim();
        if (normalized.length() > max) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is too long");
        return normalized.toUpperCase(Locale.ROOT).equals(normalized) && field.equals("category")
            ? normalized : (field.equals("status") ? normalized.toUpperCase(Locale.ROOT) : normalized);
    }
}
