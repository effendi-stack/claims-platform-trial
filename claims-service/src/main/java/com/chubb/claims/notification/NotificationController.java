package com.chubb.claims.notification;

import com.chubb.claims.common.ApiException;
import com.chubb.claims.security.CurrentUser;
import com.chubb.claims.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
@Tag(name = "Notifications & live updates")
public class NotificationController {

    private final JdbcTemplate jdbc;
    private final LiveUpdates live;
    private final CurrentUserProvider currentUser;

    public NotificationController(JdbcTemplate jdbc, LiveUpdates live, CurrentUserProvider currentUser) {
        this.jdbc = jdbc;
        this.live = live;
        this.currentUser = currentUser;
    }

    public record NotificationView(UUID id, UUID claimId, String claimNumber, String type, String message,
                                   Instant createdAt, Instant readAt) {}

    @Operation(summary = "The caller's latest notifications")
    @GetMapping("/notifications")
    public List<NotificationView> mine() {
        return jdbc.query("SELECT n.id, n.claim_id, c.claim_number, n.type, n.message, n.created_at, n.read_at "
                        + "FROM notification n JOIN claim c ON c.id = n.claim_id "
                        + "WHERE n.user_id = ? ORDER BY n.created_at DESC LIMIT 50",
                (rs, i) -> new NotificationView(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getTimestamp(6).toInstant(),
                        rs.getTimestamp(7) == null ? null : rs.getTimestamp(7).toInstant()),
                currentUser.get().id());
    }

    @Operation(summary = "Mark a notification as read")
    @PostMapping("/notifications/{id}/read")
    public void markRead(@PathVariable UUID id) {
        int updated = jdbc.update("UPDATE notification SET read_at = COALESCE(read_at, now()) WHERE id = ? AND user_id = ?",
                id, currentUser.get().id());
        if (updated == 0) {
            throw ApiException.notFound("NOTIFICATION_NOT_FOUND", "Notification not found");
        }
    }

    @Operation(summary = "Live updates (Server-Sent Events)",
            description = "EventSource cannot set headers, so this endpoint also accepts ?access_token=<jwt>.")
    @GetMapping(path = "/events/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        CurrentUser user = currentUser.get();
        return live.subscribe(user);
    }
}
