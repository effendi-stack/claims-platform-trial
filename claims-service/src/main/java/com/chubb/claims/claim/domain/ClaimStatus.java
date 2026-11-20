package com.chubb.claims.claim.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Claim lifecycle. The allowed transitions live here and nowhere else.
 *
 * SUBMITTED -> IN_REVIEW (picked up) | WITHDRAWN
 * IN_REVIEW -> AWAITING_INFO | APPROVED | REJECTED | WITHDRAWN
 * AWAITING_INFO -> IN_REVIEW (all info received) | WITHDRAWN
 * APPROVED -> SETTLED
 * SETTLED, REJECTED, WITHDRAWN are terminal.
 */
public enum ClaimStatus {
    SUBMITTED, IN_REVIEW, AWAITING_INFO, APPROVED, SETTLED, REJECTED, WITHDRAWN;

    public Set<ClaimStatus> allowedNext() {
        return switch (this) {
            case SUBMITTED -> EnumSet.of(IN_REVIEW, WITHDRAWN);
            case IN_REVIEW -> EnumSet.of(AWAITING_INFO, APPROVED, REJECTED, WITHDRAWN);
            case AWAITING_INFO -> EnumSet.of(IN_REVIEW, WITHDRAWN);
            case APPROVED -> EnumSet.of(SETTLED);
            case SETTLED, REJECTED, WITHDRAWN -> EnumSet.noneOf(ClaimStatus.class);
        };
    }

    public boolean canTransitionTo(ClaimStatus target) {
        return allowedNext().contains(target);
    }

    /** No further transitions: the claim no longer carries outstanding liability. */
    public boolean isTerminal() {
        return allowedNext().isEmpty();
    }

    /** Still being worked on: counts towards a handler's open workload. */
    public boolean isOpen() {
        return this == SUBMITTED || this == IN_REVIEW || this == AWAITING_INFO;
    }
}
