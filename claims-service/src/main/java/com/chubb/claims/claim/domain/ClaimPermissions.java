package com.chubb.claims.claim.domain;

import com.chubb.claims.common.ApiException;
import com.chubb.claims.reference.Role;
import com.chubb.claims.security.CurrentUser;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Single source of truth for "who can see / do what" on a claim. Used both to enforce
 * commands and to compute allowedActions for the UI.
 */
@Component
public class ClaimPermissions {

    /** Claimants: their own claims. Handlers: their team's claims. Manager: everything. */
    public boolean canView(Claim c, CurrentUser u) {
        return switch (u.role()) {
            case CLAIMANT -> c.getClaimantId().equals(u.id());
            case HANDLER -> c.getTeamId().equals(u.teamId());
            case MANAGER -> true;
        };
    }

    public boolean roleAllows(ClaimAction action, Claim c, CurrentUser u) {
        return switch (action) {
            case PICK_UP -> u.is(Role.HANDLER) && c.getTeamId().equals(u.teamId());
            case ASSIGN -> u.is(Role.MANAGER);
            case REQUEST_INFO, SET_RESERVE, APPROVE, REJECT, SETTLE -> isActingStaff(c, u);
            case RESPOND_TO_INFO, WITHDRAW -> u.is(Role.CLAIMANT) && c.getClaimantId().equals(u.id());
        };
    }

    /** Throws 403 if the user may never do this, 409 if the claim's state doesn't allow it now. */
    public void require(ClaimAction action, Claim c, CurrentUser u) {
        if (!roleAllows(action, c, u)) {
            throw ApiException.forbidden("ACTION_NOT_PERMITTED", "You cannot " + action + " this claim");
        }
        if (action == ClaimAction.PICK_UP && c.getAssignedHandlerId() != null) {
            throw ApiException.conflict("CLAIM_ALREADY_ASSIGNED", "This claim has already been picked up");
        }
        if (!action.allowedIn(c.getStatus(), c.getAssignedHandlerId())) {
            throw ApiException.conflict("INVALID_TRANSITION",
                    "Cannot " + action + " a claim in status " + c.getStatus());
        }
    }

    public List<ClaimAction> allowedActions(Claim c, CurrentUser u) {
        return Arrays.stream(ClaimAction.values())
                .filter(a -> roleAllows(a, c, u) && a.allowedIn(c.getStatus(), c.getAssignedHandlerId()))
                .toList();
    }

    /** The assigned handler works the claim; the manager can step in on any claim. */
    private boolean isActingStaff(Claim c, CurrentUser u) {
        return u.is(Role.MANAGER) || (u.is(Role.HANDLER) && u.id().equals(c.getAssignedHandlerId()));
    }
}
