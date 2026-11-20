package com.chubb.claims.claim.domain;

import java.time.Duration;

/** Triage priority sets the SLA for reaching a decision. */
public enum Priority {
    HIGH(Duration.ofDays(5)),
    NORMAL(Duration.ofDays(10)),
    LOW(Duration.ofDays(20));

    private final Duration decisionSla;

    Priority(Duration decisionSla) {
        this.decisionSla = decisionSla;
    }

    public Duration decisionSla() {
        return decisionSla;
    }
}
