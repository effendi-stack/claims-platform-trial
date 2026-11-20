package com.chubb.insights.api;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Read-only staff analytics. Figures are eventually consistent with claims-service, typically
 * lagging by under a second.
 */
@RestController
@RequestMapping("/api/insights")
@Tag(name = "Insights")
public class InsightsController {

    private final InsightsQueries queries;

    public InsightsController(InsightsQueries queries) {
        this.queries = queries;
    }

    @Operation(summary = "Open workload per handler, unassigned count, ageing and SLA breaches",
            description = "Handlers always get their own team. Managers may pass teamId, or omit it for all teams.")
    @GetMapping("/workload")
    @PreAuthorize("hasAnyRole('HANDLER','MANAGER')")
    public InsightsQueries.Workload workload(@AuthenticationPrincipal Jwt jwt,
                                             @RequestParam(required = false) UUID teamId) {
        return queries.workload(scopeTeam(jwt, teamId));
    }

    @Operation(summary = "Decisions and average time to decision, per handler (default: last 30 days)")
    @GetMapping("/throughput")
    @PreAuthorize("hasAnyRole('HANDLER','MANAGER')")
    public InsightsQueries.Throughput throughput(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID teamId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        Instant end = to != null ? to : Instant.now();
        Instant start = from != null ? from : end.minus(Duration.ofDays(30));
        return queries.throughput(scopeTeam(jwt, teamId), start, end);
    }

    @Operation(summary = "Outstanding liability by market x line x currency, with USD totals and the largest open reserves")
    @GetMapping("/exposure")
    @PreAuthorize("hasRole('MANAGER')")
    public InsightsQueries.Exposure exposure() {
        return queries.exposure();
    }

    /** Handlers are pinned to their own team; asking for another team is refused. */
    private static UUID scopeTeam(Jwt jwt, UUID requested) {
        if (!"HANDLER".equals(jwt.getClaimAsString("role"))) {
            return requested;
        }
        UUID own = UUID.fromString(jwt.getClaimAsString("teamId"));
        if (requested != null && !requested.equals(own)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Handlers can only view their own team");
        }
        return own;
    }
}
