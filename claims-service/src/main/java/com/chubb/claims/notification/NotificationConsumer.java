package com.chubb.claims.notification;

import com.chubb.claims.outbox.ClaimEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Turns claim events into inbox notifications and live UI pushes.
 *
 * It runs off Kafka rather than inside the command transaction, so a notification problem
 * can never roll back or slow down a claim decision. It is idempotent on eventId, because
 * delivery is at-least-once.
 */
@Component
public class NotificationConsumer {

    static final String CONSUMER = "notifications";

    private final ObjectMapper json;
    private final JdbcTemplate jdbc;
    private final LiveUpdates live;
    private final Clock clock;

    public NotificationConsumer(ObjectMapper json, JdbcTemplate jdbc, LiveUpdates live, Clock clock) {
        this.json = json;
        this.jdbc = jdbc;
        this.live = live;
        this.clock = clock;
    }

    @KafkaListener(topics = "${claims.kafka.topic}", groupId = "claims-notifications")
    @Transactional
    public void on(String payload) throws Exception {
        ClaimEvent event = json.readValue(payload, ClaimEvent.class);

        int fresh = jdbc.update("INSERT INTO processed_event (consumer, event_id, processed_at) VALUES (?, ?, ?) "
                + "ON CONFLICT DO NOTHING", CONSUMER, event.eventId(), Timestamp.from(clock.instant()));
        if (fresh == 0) {
            return; // duplicate delivery
        }

        ClaimEvent.Snapshot c = event.claim();
        String no = c.claimNumber();
        UUID claimant = c.claimantId();
        UUID handler = c.assignedHandlerId();

        switch (event.eventType()) {
            case "ClaimSubmitted" -> notify(claimant, c, event,
                    "We have received your claim " + no + ". We will keep you updated here.");
            case "ClaimAssigned" -> {
                if (event.data().get("previousHandlerId") == null) {
                    notify(claimant, c, event, "Your claim " + no + " is now being reviewed by our claims team.");
                }
                if (!Objects.equals(handler, event.actorId())) {
                    notify(handler, c, event, "Claim " + no + " has been assigned to you.");
                }
            }
            case "InformationRequested" -> notify(claimant, c, event,
                    "We need more information about claim " + no + ": " + event.data().get("question"));
            case "InformationProvided" -> notify(handler, c, event, "The claimant has responded on claim " + no + ".");
            case "ClaimApproved" -> notify(claimant, c, event, "Your claim " + no + " has been approved for "
                    + money(c.currency(), event.data().get("approvedAmount")) + ".");
            case "ClaimRejected" -> notify(claimant, c, event, "Your claim " + no
                    + " has not been approved (" + event.data().get("reasonCode") + ").");
            case "ClaimSettled" -> notify(claimant, c, event, "Payment of "
                    + money(c.currency(), event.data().get("paidAmount")) + " has been made for claim " + no + ".");
            case "ClaimWithdrawn" -> notify(handler, c, event, "Claim " + no + " was withdrawn by the claimant.");
            default -> { /* ReserveChanged etc.: internal, no notification */ }
        }

        // Live push only after the commit, so the UI never refetches state that isn't visible yet.
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                live.publish(event);
            }
        });
    }

    private void notify(UUID userId, ClaimEvent.Snapshot c, ClaimEvent e, String message) {
        if (userId == null) {
            return;
        }
        jdbc.update("INSERT INTO notification (id, user_id, claim_id, type, message, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, c.claimId(), e.eventType(), message, Timestamp.from(e.occurredAt()));
    }

    private static String money(String currency, Object amount) {
        return currency + " " + new BigDecimal(String.valueOf(amount)).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
