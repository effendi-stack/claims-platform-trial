package com.chubb.insights.projection;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * insights-service's own view of the claims.events contract ("tolerant reader"). It declares
 * only the fields it uses and ignores the rest, so claims-service can add fields without
 * breaking this consumer. No code is shared between the two services.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ClaimEventMessage(UUID eventId, String eventType, Instant occurredAt, Snapshot claim) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Snapshot(
            UUID claimId,
            String claimNumber,
            Long version,
            String status,
            String marketCode,
            String productLine,
            String currency,
            UUID teamId,
            UUID assignedHandlerId,
            String assignedHandlerName,
            BigDecimal claimedAmount,
            BigDecimal currentReserve,
            BigDecimal paidAmount,
            Instant reportedAt,
            Instant decidedAt,
            Instant slaDueAt,
            boolean slaPaused) {}

    public ClaimEventMessage validated() {
        if (eventId == null || claim == null || claim.claimId() == null || claim.version() == null
                || claim.status() == null || claim.marketCode() == null || claim.productLine() == null
                || claim.currency() == null || claim.teamId() == null || claim.reportedAt() == null) {
            throw new InvalidEventException("Event is missing required fields: " + eventId);
        }
        return this;
    }
}
