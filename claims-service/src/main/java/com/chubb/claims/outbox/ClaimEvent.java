package com.chubb.claims.outbox;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Event envelope on claims.events (Kafka key = claimId).
 *
 * Every event carries a full snapshot of the claim's reportable state ("event-carried state
 * transfer"). Consumers such as insights-service can then upsert the snapshot without
 * calling back to claims-service, and use claim.version to discard stale or duplicate
 * deliveries. "data" holds the facts specific to the event type (e.g. the question asked).
 */
public record ClaimEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID actorId,
        Snapshot claim,
        Map<String, Object> data) {

    public record Snapshot(
            UUID claimId,
            String claimNumber,
            long version,
            String status,
            String marketCode,
            String productLine,
            String currency,
            UUID teamId,
            UUID assignedHandlerId,
            UUID claimantId,
            BigDecimal claimedAmount,
            BigDecimal currentReserve,
            BigDecimal paidAmount,
            Instant reportedAt,
            Instant decidedAt,
            Instant slaDueAt,
            boolean slaPaused) {}
}
