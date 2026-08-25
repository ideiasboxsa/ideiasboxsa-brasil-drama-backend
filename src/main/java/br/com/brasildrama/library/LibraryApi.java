package br.com.brasildrama.library;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

record FavoriteDto(String dramaId) {}
record PlaybackProgressRequest(String episodeId, long positionMs, Long durationMs) {}
record PlaybackHistoryDto(String dramaId, String episodeId, long positionMs, Long durationMs, String updatedAt) {}
record ContinueWatchingItemDto(
    String dramaId,
    String dramaTitle,
    String dramaSynopsis,
    String dramaGenre,
    String episodeId,
    int episodeNumber,
    String episodeTitle,
    long positionMs,
    Long durationMs,
    String updatedAt
) {}
record ContinueWatchingResponseDto(List<ContinueWatchingItemDto> items) {}

@RestController
@RequestMapping("/v1/me")
class LibraryController {
    private final JdbcTemplate jdbc;

    LibraryController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/favorites")
    List<FavoriteDto> favorites(Authentication authentication) {
        var userId = userId(authentication);
        return jdbc.query(
            "select drama_id from user_favorite where user_id = ? order by created_at desc",
            (rs, row) -> new FavoriteDto(rs.getObject("drama_id", UUID.class).toString()),
            userId
        );
    }

    @PutMapping("/favorites/{dramaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void addFavorite(Authentication authentication, @PathVariable UUID dramaId) {
        requireDrama(dramaId);
        jdbc.update(
            "insert into user_favorite(user_id, drama_id) values (?, ?) on conflict (user_id, drama_id) do nothing",
            userId(authentication), dramaId
        );
    }

    @DeleteMapping("/favorites/{dramaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void removeFavorite(Authentication authentication, @PathVariable UUID dramaId) {
        jdbc.update("delete from user_favorite where user_id = ? and drama_id = ?", userId(authentication), dramaId);
    }

    @GetMapping("/history")
    List<PlaybackHistoryDto> history(Authentication authentication) {
        return jdbc.query(
            """
            select drama_id, episode_id, position_ms, duration_ms, updated_at
              from playback_history
             where user_id = ?
             order by updated_at desc
            """,
            (rs, row) -> new PlaybackHistoryDto(
                rs.getObject("drama_id", UUID.class).toString(),
                rs.getObject("episode_id", UUID.class).toString(),
                rs.getLong("position_ms"),
                nullableLong(rs, "duration_ms"),
                rs.getObject("updated_at", OffsetDateTime.class).toString()
            ),
            userId(authentication)
        );
    }

    @PutMapping("/history/{dramaId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    void updateProgress(
        Authentication authentication,
        @PathVariable UUID dramaId,
        @RequestBody PlaybackProgressRequest request
    ) {
        if (request.episodeId() == null || request.episodeId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "episodeId is required");
        }
        if (request.positionMs() < 0 || (request.durationMs() != null && request.durationMs() < 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Playback positions cannot be negative");
        }

        UUID episodeId;
        try {
            episodeId = UUID.fromString(request.episodeId());
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid episodeId");
        }

        Integer valid = jdbc.queryForObject(
            "select count(*) from episode where id = ? and drama_id = ?",
            Integer.class,
            episodeId, dramaId
        );
        if (valid == null || valid == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Episode does not belong to drama");
        }

        jdbc.update(
            """
            insert into playback_history(user_id, drama_id, episode_id, position_ms, duration_ms, updated_at)
            values (?, ?, ?, ?, ?, current_timestamp)
            on conflict (user_id, drama_id) do update
               set episode_id = excluded.episode_id,
                   position_ms = excluded.position_ms,
                   duration_ms = excluded.duration_ms,
                   updated_at = current_timestamp
            """,
            userId(authentication), dramaId, episodeId, request.positionMs(), request.durationMs()
        );
    }

    @GetMapping("/continue-watching")
    ContinueWatchingResponseDto continueWatching(Authentication authentication) {
        var items = jdbc.query(
            """
            select h.drama_id, d.title drama_title, d.synopsis drama_synopsis, d.genre drama_genre,
                   h.episode_id, e.number episode_number, e.title episode_title,
                   h.position_ms, h.duration_ms, h.updated_at
              from playback_history h
              join drama d on d.id = h.drama_id
              join episode e on e.id = h.episode_id
             where h.user_id = ?
               and (h.duration_ms is null or h.duration_ms = 0 or h.position_ms < h.duration_ms * 0.95)
             order by h.updated_at desc
            """,
            (rs, row) -> new ContinueWatchingItemDto(
                rs.getObject("drama_id", UUID.class).toString(),
                rs.getString("drama_title"),
                rs.getString("drama_synopsis"),
                rs.getString("drama_genre"),
                rs.getObject("episode_id", UUID.class).toString(),
                rs.getInt("episode_number"),
                rs.getString("episode_title"),
                rs.getLong("position_ms"),
                nullableLong(rs, "duration_ms"),
                rs.getObject("updated_at", OffsetDateTime.class).toString()
            ),
            userId(authentication)
        );
        return new ContinueWatchingResponseDto(items);
    }

    private UUID userId(Authentication authentication) {
        try {
            return UUID.fromString(authentication.getName());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
    }

    private void requireDrama(UUID dramaId) {
        Integer count = jdbc.queryForObject("select count(*) from drama where id = ?", Integer.class, dramaId);
        if (count == null || count == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Drama not found");
    }

    private Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
