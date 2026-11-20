package com.chubb.claims.outbox;

import com.chubb.claims.claim.domain.Claim;
import com.chubb.claims.claim.domain.Decision;
import com.chubb.claims.claim.domain.DecisionRepository;
import com.chubb.claims.claim.domain.Payment;
import com.chubb.claims.claim.domain.PaymentRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Writes a domain event into outbox_event inside the caller's transaction, so the claim change
 * and its event commit (or roll back) together. A separate relay publishes rows to Kafka.
 */
@Component
public class OutboxWriter {

    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final ObjectMapper json;
    private final DecisionRepository decisions;
    private final PaymentRepository payments;
    private final Clock clock;

    public OutboxWriter(JdbcTemplate jdbc, EntityManager em, ObjectMapper json,
                        DecisionRepository decisions, PaymentRepository payments, Clock clock) {
        this.jdbc = jdbc;
        this.em = em;
        this.json = json;
        this.decisions = decisions;
        this.payments = payments;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public UUID append(ClaimEventType type, Claim claim, UUID actorId, Map<String, Object> data) {
        // Flush so the claim's @Version is incremented and the snapshot carries the committed version.
        em.flush();

        Instant now = clock.instant();
        UUID eventId = UUID.randomUUID();
        ClaimEvent event = new ClaimEvent(eventId, type.wireName(), now, actorId, snapshot(claim), data);

        jdbc.update("INSERT INTO outbox_event (id, aggregate_id, event_type, payload, created_at) "
                        + "VALUES (?, ?, ?, ?::jsonb, ?)",
                eventId, claim.getId(), type.wireName(), toJson(event), Timestamp.from(now));
        return eventId;
    }

    private ClaimEvent.Snapshot snapshot(Claim c) {
        Instant decidedAt = decisions.findById(c.getId()).map(Decision::getDecidedAt).orElse(null);
        BigDecimal paid = payments.findByClaimIdOrderByPaidAtAsc(c.getId()).stream()
                .map(Payment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ClaimEvent.Snapshot(
                c.getId(), c.getClaimNumber(), c.getVersion(), c.getStatus().name(),
                c.getMarketCode(), c.getProductLine().name(), c.getCurrency(),
                c.getTeamId(), c.getAssignedHandlerId(), c.getClaimantId(),
                c.getClaimedAmount(), c.getCurrentReserve(), paid,
                c.getReportedAt(), decidedAt, c.getSlaDueAt(), c.getSlaPausedAt() != null);
    }

    private String toJson(ClaimEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialise event " + event.eventType(), e);
        }
    }
}
