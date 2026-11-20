package com.chubb.claims.notification;

import com.chubb.claims.outbox.ClaimEvent;
import com.chubb.claims.security.CurrentUser;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Server-Sent Events hub. Each browser holds one stream. On every claim event we push a small
 * "claim-changed" message to the users allowed to see that claim; the UI then refetches over
 * REST. The push is only a hint: REST stays the source of truth, and a missed push costs at
 * most a stale screen until the next fetch.
 *
 * Note: emitters are held in memory, so with several instances each instance must receive every
 * event (a consumer group per instance) or use a shared pub/sub. With one instance, a single
 * group is enough.
 */
@Component
public class LiveUpdates {

    private static final long TIMEOUT_MS = Duration.ofMinutes(30).toMillis();

    private final Map<SseEmitter, CurrentUser> subscribers = new ConcurrentHashMap<>();

    public SseEmitter subscribe(CurrentUser user) {
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        subscribers.put(emitter, user);
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        emitter.onError(e -> subscribers.remove(emitter));
        send(emitter, SseEmitter.event().name("connected").data("ok"));
        return emitter;
    }

    public void publish(ClaimEvent event) {
        ClaimEvent.Snapshot c = event.claim();
        Map<String, Object> message = Map.of(
                "claimId", c.claimId(),
                "claimNumber", c.claimNumber(),
                "eventType", event.eventType(),
                "status", c.status());
        subscribers.forEach((emitter, user) -> {
            if (canSee(user, c)) {
                send(emitter, SseEmitter.event().name("claim-changed").data(message));
            }
        });
    }

    /** Keeps idle connections alive through proxies, and detects dead ones. */
    @Scheduled(fixedRate = 25_000)
    void heartbeat() {
        subscribers.keySet().forEach(e -> send(e, SseEmitter.event().comment("hb")));
    }

    int subscriberCount() {
        return subscribers.size();
    }

    /** Same visibility rule as ClaimPermissions.canView, applied to the event snapshot. */
    private static boolean canSee(CurrentUser u, ClaimEvent.Snapshot c) {
        return switch (u.role()) {
            case CLAIMANT -> u.id().equals(c.claimantId());
            case HANDLER -> u.teamId() != null && u.teamId().equals(c.teamId());
            case MANAGER -> true;
        };
    }

    private void send(SseEmitter emitter, SseEmitter.SseEventBuilder event) {
        try {
            emitter.send(event);
        } catch (IOException | IllegalStateException e) {
            subscribers.remove(emitter);
        }
    }
}
