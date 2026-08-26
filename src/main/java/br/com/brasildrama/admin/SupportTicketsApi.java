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
record SupportTicketUpdateRequest(String status, String adminNote, String priority) {}
record SupportTicketMessageRequest(String message) {}
record SupportTicketRatingRequest(Integer rating, String comment) {}
record SupportTicketAssignmentRequest(Boolean assigned) {}
record SupportTicketMessageDto(UUID id, String sender, String message, OffsetDateTime createdAt) {}
record SupportTicketDto(
    UUID id, String code, UUID userId, String userEmail, String userDisplayName,
    String category, String subject, String message, String status, String adminNote,
    List<SupportTicketMessageDto> messages, Integer rating, String ratingComment, OffsetDateTime ratedAt,
    String priority, OffsetDateTime responseDueAt, boolean overdue,
    UUID assignedOperatorId, String assignedOperatorName, OffsetDateTime assignedAt,
    OffsetDateTime createdAt, OffsetDateTime updatedAt
) {}

@RestController
class SupportTicketsApi {
    private static final Set<String> CATEGORIES = Set.of("ACCOUNT", "PAYMENT", "PLAYBACK", "REWARDS", "OTHER");
    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED");
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
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
        String priority = "PAYMENT".equals(category) ? "HIGH" : "NORMAL";
        int slaHours = switch (category) {
            case "PAYMENT" -> 4;
            case "PLAYBACK" -> 8;
            case "ACCOUNT", "REWARDS" -> 12;
            default -> 24;
        };
        jdbc.update("""
            insert into support_ticket(id,user_id,category,subject,message,status,priority,response_due_at,created_at,updated_at)
            values(?,?,?,?,?,'OPEN',?,now()+(? * interval '1 hour'),now(),now())
            """, id, userId, category, subject, message, priority, slaHours);
        return find(id);
    }

    @GetMapping("/v1/me/support-tickets")
    List<SupportTicketDto> mine(Authentication authentication) {
        return jdbc.query("""
            select t.*,u.email,u.display_name from support_ticket t
            join app_user u on u.id=t.user_id
            left join admin_operator a on a.id=t.assigned_operator_id
            where t.user_id=? order by t.updated_at desc limit 20
            """, this::map, userId(authentication));
    }

    @PostMapping("/v1/me/support-tickets/{ticketId}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    SupportTicketDto reply(Authentication authentication, @PathVariable UUID ticketId, @RequestBody SupportTicketMessageRequest request) {
        UUID userId = userId(authentication);
        requireOwnedOpenTicket(ticketId, userId);
        addMessage(ticketId, "USER", request);
        return find(ticketId);
    }

    @PostMapping("/v1/me/support-tickets/{ticketId}/rating")
    @Transactional
    SupportTicketDto rate(Authentication authentication, @PathVariable UUID ticketId, @RequestBody SupportTicketRatingRequest request) {
        UUID userId = userId(authentication);
        if (request == null || request.rating() == null || request.rating() < 1 || request.rating() > 5) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_RATING");
        }
        String comment = request.comment() == null ? null : request.comment().trim();
        if (comment != null && comment.length() > 1000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "RATING_COMMENT_TOO_LONG");
        int changed = jdbc.update("""
            update support_ticket set rating=?,rating_comment=?,rated_at=now(),updated_at=now()
            where id=? and user_id=? and status in ('RESOLVED','CLOSED') and rating is null
            """, request.rating(), comment == null || comment.isBlank() ? null : comment, ticketId, userId);
        if (changed == 0) {
            Integer exists = jdbc.queryForObject("select count(*) from support_ticket where id=? and user_id=?", Integer.class, ticketId, userId);
            if (exists == null || exists == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
            throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_NOT_RATEABLE");
        }
        return find(ticketId);
    }

    @GetMapping({"/v1/admin/support-tickets", "/v1/admin/users/support-tickets"})
    List<SupportTicketDto> adminList(@RequestParam(required = false) String status) {
        if (status == null || status.isBlank()) {
            return jdbc.query("""
                select t.*,u.email,u.display_name from support_ticket t
                join app_user u on u.id=t.user_id
            left join admin_operator a on a.id=t.assigned_operator_id order by
                case when t.status in ('OPEN','IN_PROGRESS') and t.response_due_at<now() then 0 else 1 end,
                case t.priority when 'URGENT' then 0 when 'HIGH' then 1 when 'NORMAL' then 2 else 3 end,
                case t.status when 'OPEN' then 0 when 'IN_PROGRESS' then 1 when 'RESOLVED' then 2 else 3 end,
                t.updated_at desc limit 200
                """, this::map);
        }
        String normalizedStatus = status.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(normalizedStatus)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        return jdbc.query("""
            select t.*,u.email,u.display_name from support_ticket t
            join app_user u on u.id=t.user_id
            left join admin_operator a on a.id=t.assigned_operator_id where t.status=? order by t.updated_at desc limit 200
            """, this::map, normalizedStatus);
    }

    @PostMapping({"/v1/admin/support-tickets/{ticketId}/messages", "/v1/admin/users/support-tickets/{ticketId}/messages"})
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    SupportTicketDto adminReply(@PathVariable UUID ticketId, @RequestBody SupportTicketMessageRequest request) {
        requireTicket(ticketId);
        addMessage(ticketId, "ADMIN", request);
        jdbc.update("update support_ticket set status=case when status='OPEN' then 'IN_PROGRESS' else status end where id=?", ticketId);
        return find(ticketId);
    }

    @PutMapping({"/v1/admin/support-tickets/{ticketId}/assignment", "/v1/admin/users/support-tickets/{ticketId}/assignment"})
    @Transactional
    SupportTicketDto assignment(Authentication authentication, @PathVariable UUID ticketId, @RequestBody SupportTicketAssignmentRequest request) {
        boolean assigned = request != null && Boolean.TRUE.equals(request.assigned());
        int changed;
        if (assigned) {
            UUID operatorId = userId(authentication);
            changed = jdbc.update("""
                update support_ticket set assigned_operator_id=?,assigned_at=now(),
                status=case when status='OPEN' then 'IN_PROGRESS' else status end,updated_at=now() where id=?
                """, operatorId, ticketId);
        } else {
            changed = jdbc.update("update support_ticket set assigned_operator_id=null,assigned_at=null,updated_at=now() where id=?", ticketId);
        }
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
        return find(ticketId);
    }

    @PutMapping({"/v1/admin/support-tickets/{ticketId}", "/v1/admin/users/support-tickets/{ticketId}"})
    @Transactional
    SupportTicketDto update(@PathVariable UUID ticketId, @RequestBody SupportTicketUpdateRequest request) {
        String status = normalized(request == null ? null : request.status(), 24, "status");
        if (!STATUSES.contains(status)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        String note = request == null || request.adminNote() == null ? null : request.adminNote().trim();
        if (note != null && note.length() > 2000) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ADMIN_NOTE_TOO_LONG");
        String priority = request == null || request.priority() == null ? null : request.priority().trim().toUpperCase(Locale.ROOT);
        if (priority != null && !PRIORITIES.contains(priority)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_PRIORITY");
        int changed = jdbc.update("update support_ticket set status=?,admin_note=?,priority=coalesce(?,priority),updated_at=now() where id=?", status, note, priority, ticketId);
        if (changed == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
        return find(ticketId);
    }

    private void addMessage(UUID ticketId, String sender, SupportTicketMessageRequest request) {
        String message = normalized(request == null ? null : request.message(), 2000, "message");
        jdbc.update("insert into support_ticket_message(id,ticket_id,sender,message,created_at) values(?,?,?,?,now())",
            UUID.randomUUID(), ticketId, sender, message);
        jdbc.update("update support_ticket set updated_at=now() where id=?", ticketId);
    }

    private void requireOwnedOpenTicket(UUID ticketId, UUID userId) {
        List<String> statuses = jdbc.query("select status from support_ticket where id=? and user_id=?",
            (rs, row) -> rs.getString(1), ticketId, userId);
        if (statuses.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
        if ("CLOSED".equals(statuses.getFirst())) throw new ResponseStatusException(HttpStatus.CONFLICT, "TICKET_CLOSED");
    }

    private void requireTicket(UUID ticketId) {
        Integer count = jdbc.queryForObject("select count(*) from support_ticket where id=?", Integer.class, ticketId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
    }

    private SupportTicketDto find(UUID id) {
        List<SupportTicketDto> rows = jdbc.query("""
            select t.*,u.email,u.display_name from support_ticket t
            join app_user u on u.id=t.user_id
            left join admin_operator a on a.id=t.assigned_operator_id where t.id=?
            """, this::map, id);
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "TICKET_NOT_FOUND");
        return rows.getFirst();
    }

    private List<SupportTicketMessageDto> messages(UUID ticketId) {
        return jdbc.query("""
            select id,sender,message,created_at from support_ticket_message
            where ticket_id=? order by created_at,id
            """, (rs, row) -> new SupportTicketMessageDto(
                rs.getObject("id", UUID.class), rs.getString("sender"),
                rs.getString("message"), rs.getObject("created_at", OffsetDateTime.class)
            ), ticketId);
    }

    private SupportTicketDto map(ResultSet rs, int row) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        OffsetDateTime responseDueAt = rs.getObject("response_due_at", OffsetDateTime.class);
        String status = rs.getString("status");
        boolean overdue = responseDueAt != null && responseDueAt.isBefore(OffsetDateTime.now()) && ("OPEN".equals(status) || "IN_PROGRESS".equals(status));
        return new SupportTicketDto(
            id, "BD-" + id.toString().substring(0, 8).toUpperCase(Locale.ROOT),
            rs.getObject("user_id", UUID.class), rs.getString("email"), rs.getString("display_name"),
            rs.getString("category"), rs.getString("subject"), rs.getString("message"),
            status, rs.getString("admin_note"), messages(id),
            (Integer) rs.getObject("rating"), rs.getString("rating_comment"), rs.getObject("rated_at", OffsetDateTime.class),
            rs.getString("priority"), responseDueAt, overdue,
            rs.getObject("assigned_operator_id", UUID.class), rs.getString("assigned_operator_name"), rs.getObject("assigned_at", OffsetDateTime.class),
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
        return field.equals("category") || field.equals("status")
            ? normalized.toUpperCase(Locale.ROOT) : normalized;
    }
}
