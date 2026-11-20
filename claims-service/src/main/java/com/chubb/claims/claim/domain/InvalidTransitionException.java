package com.chubb.claims.claim.domain;

import com.chubb.claims.common.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidTransitionException extends ApiException {

    public InvalidTransitionException(ClaimStatus from, ClaimStatus to) {
        super(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                "A claim cannot move from " + from + " to " + to);
    }
}
