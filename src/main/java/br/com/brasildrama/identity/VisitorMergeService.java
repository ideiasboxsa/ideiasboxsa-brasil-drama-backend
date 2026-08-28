package br.com.brasildrama.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class VisitorMergeService {
    private final JdbcTemplate jdbc;

    public VisitorMergeService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public VisitorMergeResult merge(String rawVisitorId, UUID userId) {
        if (rawVisitorId == null || rawVisitorId.isBlank()) {
            return VisitorMergeResult.none();
        }

        var visitor = VisitorIdentity.parse(rawVisitorId);
        var linkedUser = jdbc.query(
            "select merged_user_id from visitor_identity where visitor_id=?",
            rs -> rs.next() ? (UUID) rs.getObject("merged_user_id") : null,
            visitor.id()
        );

        if (linkedUser != null && !linkedUser.equals(userId)) {
            return new VisitorMergeResult(false, false, 0, 0);
        }

        jdbc.update(
            """
            insert into visitor_identity(visitor_id,first_seen_at,last_seen_at,merged_user_id,merged_at)
            values (?,now(),now(),?,now())
            on conflict (visitor_id) do update
               set last_seen_at=excluded.last_seen_at,
                   merged_user_id=coalesce(visitor_identity.merged_user_id, excluded.merged_user_id),
                   merged_at=coalesce(visitor_identity.merged_at, excluded.merged_at)
            """,
            visitor.id(),
            userId
        );

        int likesCopied = jdbc.update(
            """
            insert into drama_like(id,drama_id,user_id,visitor_id,created_at)
            select gen_random_uuid(), drama_id, ?, null, created_at
              from drama_like
             where visitor_id=?
            on conflict do nothing
            """,
            userId,
            visitor.externalId()
        );
        jdbc.update("delete from drama_like where visitor_id=?", visitor.externalId());

        int playbackEventsLinked = jdbc.update(
            """
            update playback_event
               set user_id=?
             where visitor_id=?
               and user_id is null
            """,
            userId,
            visitor.externalId()
        );

        return new VisitorMergeResult(true, linkedUser == null, likesCopied, playbackEventsLinked);
    }

    public record VisitorMergeResult(
        boolean accepted,
        boolean newlyMerged,
        int likesCopied,
        int playbackEventsLinked
    ) {
        static VisitorMergeResult none() {
            return new VisitorMergeResult(false, false, 0, 0);
        }
    }
}
