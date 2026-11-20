package com.chubb.claims.claim.domain;

import com.chubb.claims.common.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** A settlement payment recorded against the claim. Executing payments is out of scope. */
@Entity
@Table(name = "payment")
public class Payment extends BaseEntity {

    @Id
    private UUID id;
    private UUID claimId;
    private BigDecimal amount;
    private String currency;
    private String reference;
    private Instant paidAt;

    protected Payment() {
    }

    public Payment(Claim claim, BigDecimal amount, String reference, Instant at) {
        this.id = UUID.randomUUID();
        this.claimId = claim.getId();
        this.amount = amount;
        this.currency = claim.getCurrency();
        this.reference = reference;
        this.paidAt = at;
    }

    @Override
    public UUID getId() { return id; }
    public UUID getClaimId() { return claimId; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrency() { return currency; }
    public String getReference() { return reference; }
    public Instant getPaidAt() { return paidAt; }
}
