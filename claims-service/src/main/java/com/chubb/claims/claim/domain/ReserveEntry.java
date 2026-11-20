package com.chubb.claims.claim.domain;

import com.chubb.claims.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Exposure ledger row: every change to a claim's reserve, with who and why. Append-only. */
@Entity
@Table(name = "reserve_entry")
public class ReserveEntry extends BaseEntity {

    @Id
    private UUID id;
    private UUID claimId;
    private BigDecimal amount;
    private String currency;
    private String reason;
    /** null = set by the system (initial reserve rule). */
    private UUID setBy;
    private Instant setAt;

    protected ReserveEntry() {
    }

    public ReserveEntry(Claim claim, BigDecimal amount, String reason, UUID setBy, Instant at) {
        this.id = UUID.randomUUID();
        this.claimId = claim.getId();
        this.amount = amount;
        this.currency = claim.getCurrency();
        this.reason = reason;
        this.setBy = setBy;
        this.setAt = at;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReason() { return reason; }
    public UUID getSetBy() { return setBy; }
    public Instant getSetAt() { return setAt; }
}
