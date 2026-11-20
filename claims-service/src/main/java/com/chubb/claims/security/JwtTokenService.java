package com.chubb.claims.security;

import com.chubb.claims.reference.AppUser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

@Service
public class JwtTokenService {

    public static final String CLAIM_ROLE = "role";
    public static final String CLAIM_TEAM = "teamId";

    private final JwtEncoder encoder;
    private final Clock clock;
    private final Duration ttl;

    public JwtTokenService(JwtEncoder encoder, Clock clock, @Value("${security.jwt.ttl}") Duration ttl) {
        this.encoder = encoder;
        this.clock = clock;
        this.ttl = ttl;
    }

    public String issue(AppUser user) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("claims-platform")
                .subject(user.getId().toString())
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim(CLAIM_ROLE, user.getRole().name())
                .claim("name", user.getName());
        if (user.getTeamId() != null) {
            claims.claim(CLAIM_TEAM, user.getTeamId().toString());
        }
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
}
