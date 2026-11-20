package com.chubb.claims.claim.application;

import com.chubb.claims.claim.api.ClaimRequests.SubmitClaim;
import com.chubb.claims.claim.domain.Priority;
import com.chubb.claims.common.ApiException;
import com.chubb.claims.reference.Policy;
import com.chubb.claims.reference.ProductLine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

/** First-notice-of-loss rules: line-specific details, triage priority, claim numbering. */
@Component
public class ClaimIntake {

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};

    private final ObjectMapper json;
    private final JdbcTemplate jdbc;

    public ClaimIntake(ObjectMapper json, JdbcTemplate jdbc) {
        this.json = json;
        this.jdbc = jdbc;
    }

    /** The details block must match the policy's line: motor details for motor, property for property. */
    public Map<String, Object> lineDetails(Policy policy, SubmitClaim req) {
        Object details;
        if (policy.getProductLine() == ProductLine.MOTOR) {
            details = require(req.motor(), req.property(), "motor");
        } else {
            details = require(req.property(), req.motor(), "property");
        }
        return json.convertValue(details, MAP);
    }

    /** Injuries or an uninhabitable home are urgent: shorter decision SLA. */
    public Priority triage(SubmitClaim req) {
        if (req.motor() != null && Boolean.TRUE.equals(req.motor().injuries())) {
            return Priority.HIGH;
        }
        if (req.property() != null && Boolean.FALSE.equals(req.property().habitable())) {
            return Priority.HIGH;
        }
        return Priority.NORMAL;
    }

    /** Human-readable, e.g. SG-MOT-2026-001000. Sequence-backed, so unique under concurrency. */
    public String nextClaimNumber(Policy policy, Instant now) {
        Long seq = jdbc.queryForObject("SELECT nextval('claim_number_seq')", Long.class);
        int year = now.atZone(ZoneOffset.UTC).getYear();
        return String.format("%s-%s-%d-%06d", policy.getMarketCode(), policy.getProductLine().code(), year, seq);
    }

    private static Object require(Object expected, Object other, String name) {
        if (expected == null || other != null) {
            throw ApiException.unprocessable("DETAILS_MISMATCH",
                    "This policy requires '" + name + "' details and no other details block");
        }
        return expected;
    }
}
