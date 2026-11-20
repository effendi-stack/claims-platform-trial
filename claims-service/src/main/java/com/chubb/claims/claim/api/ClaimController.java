package com.chubb.claims.claim.api;

import com.chubb.claims.claim.api.ClaimResponses.ClaimDetail;
import com.chubb.claims.claim.api.ClaimResponses.ClaimSummary;
import com.chubb.claims.claim.application.ClaimCommandService;
import com.chubb.claims.claim.application.ClaimCommandService.SubmitResult;
import com.chubb.claims.claim.application.ClaimQueryService;
import com.chubb.claims.claim.application.ClaimQueryService.ClaimFilter;
import com.chubb.claims.claim.domain.ClaimStatus;
import com.chubb.claims.common.PageResponse;
import com.chubb.claims.reference.ProductLine;
import com.chubb.claims.security.CurrentUser;
import com.chubb.claims.security.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import org.springdoc.core.annotations.ParameterObject;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.util.List;
import java.util.UUID;

/**
 * Claims API. State changes are explicit action endpoints (POST /claims/{id}/approve...) rather
 * than PATCH status. Each command returns the claim's new state, including allowedActions,
 * so the UI can re-render without guessing what is possible next.
 *
 * Role checks here are coarse (@PreAuthorize); ownership and state rules are in ClaimPermissions.
 */
@RestController
@RequestMapping("/api/claims")
@Tag(name = "Claims")
public class ClaimController {

    private final ClaimCommandService commands;
    private final ClaimQueryService queries;
    private final CurrentUserProvider currentUser;

    public ClaimController(ClaimCommandService commands, ClaimQueryService queries, CurrentUserProvider currentUser) {
        this.commands = commands;
        this.queries = queries;
        this.currentUser = currentUser;
    }

    // ------------------------------------------------------------------ queries

    @Operation(summary = "List claims visible to the caller",
            description = "Claimant: own claims. Handler: own team. Manager: all. assignedTo = me | unassigned | <handlerId>.")
    @GetMapping
    public PageResponse<ClaimSummary> list(@RequestParam(required = false) List<ClaimStatus> status,
                                           @RequestParam(required = false) String assignedTo,
                                           @RequestParam(required = false) String market,
                                           @RequestParam(required = false) ProductLine productLine,
                                           @ParameterObject @PageableDefault(size = 20) Pageable pageable) {
        return queries.list(currentUser.get(), new ClaimFilter(status, assignedTo, market, productLine), pageable);
    }

    @Operation(summary = "Claim detail with timeline, information requests, decision and allowed actions")
    @GetMapping("/{id}")
    public ClaimDetail get(@PathVariable UUID id) {
        return queries.get(currentUser.get(), id);
    }

    // ------------------------------------------------------------------ claimant commands

    @Operation(summary = "Report an incident (first notice of loss)",
            description = "Send an Idempotency-Key header to make retries safe: the same key returns the same claim.")
    @PostMapping
    @PreAuthorize("hasRole('CLAIMANT')")
    public ResponseEntity<ClaimDetail> submit(@Valid @RequestBody ClaimRequests.SubmitClaim body,
                                              @RequestHeader(name = "Idempotency-Key", required = false) String key) {
        CurrentUser user = currentUser.get();
        SubmitResult result = commands.submit(user, body, key);
        ClaimDetail detail = queries.get(user, result.claimId());
        if (!result.created()) {
            return ResponseEntity.ok(detail);
        }
        var location = ServletUriComponentsBuilder.fromCurrentRequest().path("/{id}").buildAndExpand(result.claimId()).toUri();
        return ResponseEntity.created(location).body(detail);
    }

    @Operation(summary = "Answer an information request")
    @PostMapping("/{id}/information-requests/{requestId}/response")
    @PreAuthorize("hasRole('CLAIMANT')")
    public ClaimDetail respond(@PathVariable UUID id, @PathVariable UUID requestId,
                               @Valid @RequestBody ClaimRequests.RespondToInformation body) {
        CurrentUser user = currentUser.get();
        commands.respondToInformation(user, id, requestId, body);
        return queries.get(user, id);
    }

    @Operation(summary = "Withdraw the claim")
    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasRole('CLAIMANT')")
    public ClaimDetail withdraw(@PathVariable UUID id, @Valid @RequestBody(required = false) ClaimRequests.Withdraw body) {
        CurrentUser user = currentUser.get();
        commands.withdraw(user, id, body == null ? new ClaimRequests.Withdraw(null) : body);
        return queries.get(user, id);
    }

    // ------------------------------------------------------------------ staff commands

    @Operation(summary = "Pick up (handler, no body) or assign to a handler (manager)")
    @PostMapping("/{id}/assign")
    @PreAuthorize("hasAnyRole('HANDLER','MANAGER')")
    public ClaimDetail assign(@PathVariable UUID id, @RequestBody(required = false) ClaimRequests.Assign body) {
        CurrentUser user = currentUser.get();
        commands.assign(user, id, body);
        return queries.get(user, id);
    }

    @Operation(summary = "Ask the claimant for more information (pauses the SLA)")
    @PostMapping("/{id}/information-requests")
    @PreAuthorize("hasAnyRole('HANDLER','MANAGER')")
    public ClaimDetail requestInformation(@PathVariable UUID id,
                                          @Valid @RequestBody ClaimRequests.RequestInformation body) {
        CurrentUser user = currentUser.get();
        commands.requestInformation(user, id, body);
        return queries.get(user, id);
    }

    @Operation(summary = "Revise the reserve (estimated liability)")
    @PostMapping("/{id}/reserve")
    @PreAuthorize("hasAnyRole('HANDLER','MANAGER')")
    public ClaimDetail setReserve(@PathVariable UUID id, @Valid @RequestBody ClaimRequests.SetReserve body) {
        CurrentUser user = currentUser.get();
        commands.setReserve(user, id, body);
        return queries.get(user, id);
    }

    @Operation(summary = "Approve for settlement (subject to the handler's authority limit)")
    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAnyRole('HANDLER','MANAGER')")
    public ClaimDetail approve(@PathVariable UUID id, @Valid @RequestBody ClaimRequests.Approve body) {
        CurrentUser user = currentUser.get();
        commands.approve(user, id, body);
        return queries.get(user, id);
    }

    @Operation(summary = "Reject with a reason code")
    @PostMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('HANDLER','MANAGER')")
    public ClaimDetail reject(@PathVariable UUID id, @Valid @RequestBody ClaimRequests.Reject body) {
        CurrentUser user = currentUser.get();
        commands.reject(user, id, body);
        return queries.get(user, id);
    }

    @Operation(summary = "Record settlement payment of the approved amount")
    @PostMapping("/{id}/settle")
    @PreAuthorize("hasAnyRole('HANDLER','MANAGER')")
    public ClaimDetail settle(@PathVariable UUID id, @Valid @RequestBody ClaimRequests.Settle body) {
        CurrentUser user = currentUser.get();
        commands.settle(user, id, body);
        return queries.get(user, id);
    }
}
