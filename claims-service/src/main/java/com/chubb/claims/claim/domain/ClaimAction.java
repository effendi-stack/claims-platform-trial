package com.chubb.claims.claim.domain;

import java.util.UUID;

/**
 * Everything a user can do to a claim. allowedIn() is the status precondition;
 * who may do it is decided by ClaimPermissions. The API returns the actions that pass both
 * as allowedActions, so the UI never duplicates these rules.
 */
public enum ClaimAction {
    PICK_UP, ASSIGN, REQUEST_INFO, RESPOND_TO_INFO, SET_RESERVE, APPROVE, REJECT, SETTLE, WITHDRAW;

    public boolean allowedIn(ClaimStatus s, UUID assignedHandlerId) {
        return switch (this) {
            case PICK_UP -> s == ClaimStatus.SUBMITTED && assignedHandlerId == null;
            case ASSIGN, WITHDRAW -> s.isOpen();
            case REQUEST_INFO, SET_RESERVE -> s == ClaimStatus.IN_REVIEW || s == ClaimStatus.AWAITING_INFO;
            case RESPOND_TO_INFO -> s == ClaimStatus.AWAITING_INFO;
            case APPROVE, REJECT -> s == ClaimStatus.IN_REVIEW;
            case SETTLE -> s == ClaimStatus.APPROVED;
        };
    }
}
