package com.chubb.claims.claim.application;

import com.chubb.claims.claim.domain.Claim;
import com.chubb.claims.claim.domain.ClaimStatus;
import com.chubb.claims.common.ApiException;
import com.chubb.claims.reference.ProductLine;
import com.chubb.claims.security.CurrentUser;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.UUID;

/** Composable list filters. visibleTo() is always applied: it is the row-level security for lists. */
final class ClaimSpecifications {

    private ClaimSpecifications() {
    }

    static Specification<Claim> visibleTo(CurrentUser user) {
        return (root, query, cb) -> switch (user.role()) {
            case CLAIMANT -> cb.equal(root.get("claimantId"), user.id());
            case HANDLER -> cb.equal(root.get("teamId"), user.teamId());
            case MANAGER -> cb.conjunction();
        };
    }

    static Specification<Claim> statusIn(List<ClaimStatus> statuses) {
        return (root, query, cb) -> statuses == null || statuses.isEmpty()
                ? cb.conjunction()
                : root.get("status").in(statuses);
    }

    static Specification<Claim> assignedTo(String assignedTo, CurrentUser user) {
        return (root, query, cb) -> {
            if (assignedTo == null || assignedTo.isBlank()) {
                return cb.conjunction();
            }
            if ("me".equalsIgnoreCase(assignedTo)) {
                return cb.equal(root.get("assignedHandlerId"), user.id());
            }
            if ("unassigned".equalsIgnoreCase(assignedTo)) {
                return cb.isNull(root.get("assignedHandlerId"));
            }
            try {
                return cb.equal(root.get("assignedHandlerId"), UUID.fromString(assignedTo));
            } catch (IllegalArgumentException e) {
                throw ApiException.unprocessable("INVALID_FILTER", "assignedTo must be 'me', 'unassigned' or a user id");
            }
        };
    }

    static Specification<Claim> market(String marketCode) {
        return (root, query, cb) -> marketCode == null ? cb.conjunction() : cb.equal(root.get("marketCode"), marketCode);
    }

    static Specification<Claim> line(ProductLine line) {
        return (root, query, cb) -> line == null ? cb.conjunction() : cb.equal(root.get("productLine"), line);
    }
}
