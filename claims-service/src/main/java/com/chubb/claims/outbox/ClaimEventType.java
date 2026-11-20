package com.chubb.claims.outbox;

/** Domain events published on claims.events. The name is the wire value of "eventType". */
public enum ClaimEventType {
    CLAIM_SUBMITTED("ClaimSubmitted"),
    CLAIM_ASSIGNED("ClaimAssigned"),
    INFORMATION_REQUESTED("InformationRequested"),
    INFORMATION_PROVIDED("InformationProvided"),
    RESERVE_CHANGED("ReserveChanged"),
    CLAIM_APPROVED("ClaimApproved"),
    CLAIM_REJECTED("ClaimRejected"),
    CLAIM_SETTLED("ClaimSettled"),
    CLAIM_WITHDRAWN("ClaimWithdrawn");

    private final String wireName;

    ClaimEventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }
}
