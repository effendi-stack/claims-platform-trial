package com.chubb.claims.security;

import com.chubb.claims.reference.Role;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class CurrentUserProvider {

    public CurrentUser get() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            throw new AuthenticationCredentialsNotFoundException("No authenticated user");
        }
        String team = jwt.getClaimAsString(JwtTokenService.CLAIM_TEAM);
        return new CurrentUser(
                UUID.fromString(jwt.getSubject()),
                Role.valueOf(jwt.getClaimAsString(JwtTokenService.CLAIM_ROLE)),
                team == null ? null : UUID.fromString(team));
    }
}
