package com.chubb.claims.claim.domain;

import com.chubb.claims.reference.Policy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Rules for the reserve (the insurer's estimate of what it will pay). */
public final class ReservePolicy {

    private ReservePolicy() {
    }

    /**
     * Initial reserve on first notice of loss: what we could pay if the claim is valid as stated,
     * i.e. the claimed amount capped at the sum insured, less the deductible, never negative.
     * The handler revises it as the assessment progresses.
     */
    public static BigDecimal initialReserve(BigDecimal claimedAmount, Policy policy) {
        return claimedAmount.min(policy.getSumInsured())
                .subtract(policy.getDeductible())
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
