package br.com.brasildrama.catalog;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

record DramaLikeDto(boolean liked, long count) {}

@RestController
@RequestMapping("/v1/catalog/dramas/{dramaId}/likes")
class DramaLikesApi {
    private final JdbcTemplate jdbc;

    DramaLikesApi(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    DramaLikeDto status(
        @PathVariable UUID dramaId,
        Authentication authentication,
        @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId
    ) {
        ensureDrama(dramaId);
        var principal = principal(authentication, visitorId);
        return snapshot(dramaId, principal);
    }

    @PutMapping
    @Transactional
    DramaLikeDto like(
        @PathVariable UUID dramaId,
        Authentication authentication,
        @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId
    ) {
        ensureDrama(dramaId);
        var principal = principal(authentication, visitorId);
        if (principal.userId() != null) {
            jdbc.update("""
                insert into drama_like(id,drama_id,user_id,created_at) values (?,?,?,now())
                on conflict (drama_id,user_id) where user_id is not null do nothing
                """, UUID.randomUUID(), dramaId, principal.userId());
        } else {
            jdbc.update("""
                insert into drama_like(id,drama_id,visitor_id,created_at) values (?,?,?,now())
                on conflict (drama_id,visitor_id) where visitor_id is not null do nothing
                """, UUID.randomUUID(), dramaId, principal.visitorId());
        }
        return snapshot(dramaId, principal);
    }

    @DeleteMapping
    @Transactional
    DramaLikeDto unlike(
        @PathVariable UUID dramaId,
        Authentication authentication,
        @RequestHeader(value = "X-Visitor-ID", required = false) String visitorId
    ) {
        ensureDrama(dramaId);
        var principal = principal(authentication, visitorId);
        if (principal.userId() != null) {
            jdbc.update("delete from drama_like where drama_id=? and user_id=?", dramaId, principal.userId());
        } else {
            jdbc.update("delete from drama_like where drama_id=? and visitor_id=?", dramaId, principal.visitorId());
        }
        return snapshot(dramaId, principal);
    }

    private DramaLikeDto snapshot(UUID dramaId, Principal principal) {
        long count = jdbc.queryForObject("select count(*) from drama_like where drama_id=?", Long.class, dramaId);
        boolean liked = principal.userId() != null
            ? Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from drama_like where drama_id=? and user_id=?)", Boolean.class, dramaId, principal.userId()))
            : Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from drama_like where drama_id=? and visitor_id=?)", Boolean.class, dramaId, principal.visitorId()));
        return new DramaLikeDto(liked, count);
    }

    private Principal principal(Authentication authentication, String visitorId) {
        if (authentication != null && authentication.isAuthenticated()) {
            try { return new Principal(UUID.fromString(authentication.getName()), null); }
            catch (IllegalArgumentException ignored) { }
        }
        var normalized = visitorId == null ? "" : visitorId.trim();
        if (normalized.length() < 16 || normalized.length() > 64 || !normalized.matches("[A-Za-z0-9._:-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Valid X-Visitor-ID is required");
        }
        return new Principal(null, normalized);
    }

    private void ensureDrama(UUID dramaId) {
        Boolean exists = jdbc.queryForObject("select exists(select 1 from drama where id=?)", Boolean.class, dramaId);
        if (!Boolean.TRUE.equals(exists)) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Drama not found");
    }

    private record Principal(UUID userId, String visitorId) {}
}
