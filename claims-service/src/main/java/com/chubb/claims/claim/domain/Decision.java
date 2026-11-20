package com.chubb.claims.claim.domain;

import com.chubb.claims.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** The single approve/reject decision on a claim (keyed by claim id). */
@Entity
@Table(name = "decision")
public class Decision extends BaseEntity {

    public enum Outcome { APPROVED, REJECTED }

    @Id
    private UUID claimId;

    @Enumerated(EnumType.STRING)
    private Outcome outcome;

    private BigDecimal approvedAmount;
    private String reasonCode;
    private String notes;
    private UUID decidedBy;
    private Instant decidedAt;

    protected Decision() {
    }

    public static Decision approved(UUID claimId, BigDecimal amount, String notes, UUID by, Instant at) {
        return new Decision(claimId, Outcome.APPROVED, amount, "COVERED", notes, by, at);
    }

    public static Decision rejected(UUID claimId, RejectionReason reason, String notes, UUID by, Instant at) {
        return new Decision(claimId, Outcome.REJECTED, null, reason.name(), notes, by, at);
    }

    private Decision(UUID claimId, Outcome outcome, BigDecimal amount, String reasonCode, String notes,
                     UUID by, Instant at) {
        this.claimId = claimId;
        this.outcome = outcome;
        this.approvedAmount = amount;
        this.reasonCode = reasonCode;
        this.notes = notes;
        this.decidedBy = by;
        this.decidedAt = at;
    }

    @Override
    public UUID getId() { return claimId; }
    public Outcome getOutcome() { return outcome; }
    public BigDecimal getApprovedAmount() { return approvedAmount; }
    public String getReasonCode() { return reasonCode; }
    public String getNotes() { return notes; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
}
