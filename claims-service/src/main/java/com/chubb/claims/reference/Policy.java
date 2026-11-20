package com.chubb.claims.reference;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Seeded: policy administration is out of scope for this platform. */
@Entity
@Table(name = "policy")
public class Policy {

    @Id
    private UUID id;
    private String policyNumber;
    private UUID holderId;

    @Enumerated(EnumType.STRING)
    private ProductLine productLine;

    private String marketCode;
    private String currency;
    private BigDecimal sumInsured;
    private BigDecimal deductible;
    private LocalDate effectiveFrom;
    private LocalDate effectiveTo;

    protected Policy() {
    }

    /** Cover runs from effectiveFrom (inclusive) to effectiveTo (exclusive). */
    public boolean covers(LocalDate date) {
        return !date.isBefore(effectiveFrom) && date.isBefore(effectiveTo);
    }

    public UUID getId() { return id; }
    public String getPolicyNumber() { return policyNumber; }
    public UUID getHolderId() { return holderId; }
    public ProductLine getProductLine() { return productLine; }
    public String getMarketCode() { return marketCode; }
    public String getCurrency() { return currency; }
    public BigDecimal getSumInsured() { return sumInsured; }
    public BigDecimal getDeductible() { return deductible; }
    public LocalDate getEffectiveFrom() { return effectiveFrom; }
    public LocalDate getEffectiveTo() { return effectiveTo; }
}
