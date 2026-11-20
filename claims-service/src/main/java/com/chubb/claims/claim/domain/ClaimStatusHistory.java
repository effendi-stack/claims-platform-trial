package com.chubb.claims.claim.domain;

import com.chubb.claims.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** Audit trail of status changes; also the claimant's timeline. Append-only. */
@Entity
@Table(name = "claim_status_history")
public class ClaimStatusHistory extends BaseEntity {

    @Id
    private UUID id;
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    private ClaimStatus fromStatus;

    @Enumerated(EnumType.STRING)
    private ClaimStatus toStatus;

    private UUID actorId;
    private String reason;
    private Instant occurredAt;

    protected ClaimStatusHistory() {
    }

    public ClaimStatusHistory(UUID claimId, ClaimStatus from, ClaimStatus to, UUID actorId, String reason, Instant at) {
        this.id = UUID.randomUUID();
        this.claimId = claimId;
        this.fromStatus = from;
        this.toStatus = to;
        this.actorId = actorId;
        this.reason = reason;
        this.occurredAt = at;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public ClaimStatus getFromStatus() { return fromStatus; }
    public ClaimStatus getToStatus() { return toStatus; }
    public UUID getActorId() { return actorId; }
    public String getReason() { return reason; }
    public Instant getOccurredAt() { return occurredAt; }
}
