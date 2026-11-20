package com.chubb.claims.security;

import com.chubb.claims.reference.Role;

import java.util.UUID;

/** The authenticated caller, taken from the JWT. teamId is null for claimants and the regional manager. */
public record CurrentUser(UUID id, Role role, UUID teamId) {

    public boolean is(Role r) {
        return role == r;
    }
}
