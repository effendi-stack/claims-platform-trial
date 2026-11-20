package com.chubb.claims.claim.application;

import com.chubb.claims.claim.api.ClaimRequests;
import com.chubb.claims.claim.domain.Claim;
import com.chubb.claims.claim.domain.Claim.StatusChange;
import com.chubb.claims.claim.domain.ClaimAction;
import com.chubb.claims.claim.domain.ClaimPermissions;
import com.chubb.claims.claim.domain.ClaimRepository;
import com.chubb.claims.claim.domain.ClaimStatusHistory;
import com.chubb.claims.claim.domain.ClaimStatusHistoryRepository;
import com.chubb.claims.claim.domain.Decision;
import com.chubb.claims.claim.domain.DecisionRepository;
import com.chubb.claims.claim.domain.InformationRequest;
import com.chubb.claims.claim.domain.InformationRequestRepository;
import com.chubb.claims.claim.domain.Payment;
import com.chubb.claims.claim.domain.PaymentRepository;
import com.chubb.claims.claim.domain.Priority;
import com.chubb.claims.claim.domain.ReserveEntry;
import com.chubb.claims.claim.domain.ReserveEntryRepository;
import com.chubb.claims.claim.domain.ReservePolicy;
import com.chubb.claims.common.ApiException;
import com.chubb.claims.outbox.ClaimEventType;
import com.chubb.claims.outbox.OutboxWriter;
import com.chubb.claims.reference.AppUser;
import com.chubb.claims.reference.AppUserRepository;
import com.chubb.claims.reference.Policy;
import com.chubb.claims.reference.PolicyRepository;
import com.chubb.claims.reference.Role;
import com.chubb.claims.reference.TeamRepository;
import com.chubb.claims.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Use cases that change a claim. Each method is one transaction that:
 *   1. loads the claim (404 if the caller can't see it),
 *   2. checks permission and state (ClaimPermissions),
 *   3. applies the change on the aggregate,
 *   4. records history / ledger rows,
 *   5. appends a domain event to the outbox, in the same transaction.
 */
@Service
@Transactional
public class ClaimCommandService {

    private final ClaimRepository claims;
    private final ClaimStatusHistoryRepository history;
    private final InformationRequestRepository infoRequests;
    private final ReserveEntryRepository reserves;
    private final DecisionRepository decisions;
    private final PaymentRepository payments;
    private final PolicyRepository policies;
    private final TeamRepository teams;
    private final AppUserRepository users;
    private final ClaimPermissions permissions;
    private final ClaimIntake intake;
    private final OutboxWriter outbox;
    private final Clock clock;

    public ClaimCommandService(ClaimRepository claims, ClaimStatusHistoryRepository history,
                               InformationRequestRepository infoRequests, ReserveEntryRepository reserves,
                               DecisionRepository decisions, PaymentRepository payments,
                               PolicyRepository policies, TeamRepository teams, AppUserRepository users,
                               ClaimPermissions permissions, ClaimIntake intake, OutboxWriter outbox, Clock clock) {
        this.claims = claims;
        this.history = history;
        this.infoRequests = infoRequests;
        this.reserves = reserves;
        this.decisions = decisions;
        this.payments = payments;
        this.policies = policies;
        this.teams = teams;
        this.users = users;
        this.permissions = permissions;
        this.intake = intake;
        this.outbox = outbox;
        this.clock = clock;
    }

    public record SubmitResult(UUID claimId, boolean created) {}

    // ------------------------------------------------------------------ claimant

    public SubmitResult submit(CurrentUser user, ClaimRequests.SubmitClaim req, String idempotencyKey) {
        if (idempotencyKey != null) {
            var existing = claims.findByClaimantIdAndSubmissionKey(user.id(), idempotencyKey);
            if (existing.isPresent()) {
                return new SubmitResult(existing.get().getId(), false); // client retry: same claim back
            }
        }

        // A claimant can only claim on their own policy; anything else looks like "not found".
        Policy policy = policies.findById(req.policyId())
                .filter(p -> p.getHolderId().equals(user.id()))
                .orElseThrow(() -> ApiException.notFound("POLICY_NOT_FOUND", "Policy not found"));
        if (!policy.covers(req.incidentDate())) {
            throw ApiException.unprocessable("POLICY_NOT_IN_FORCE", "The policy was not in force on the incident date");
        }

        Map<String, Object> details = intake.lineDetails(policy, req);
        Priority priority = intake.triage(req);
        UUID teamId = teams.findByMarketCode(policy.getMarketCode())
                .orElseThrow(() -> new IllegalStateException("No claims team for market " + policy.getMarketCode()))
                .getId();
        BigDecimal initialReserve = ReservePolicy.initialReserve(req.claimedAmount(), policy);
        Instant now = clock.instant();

        Claim claim = Claim.submit(UUID.randomUUID(), intake.nextClaimNumber(policy, now), policy, user.id(), teamId,
                req.incidentDate(), req.description(), req.claimedAmount(), details, priority, initialReserve,
                idempotencyKey, now);
        claims.save(claim);
        history.save(new ClaimStatusHistory(claim.getId(), null, claim.getStatus(), user.id(), null, now));
        reserves.save(new ReserveEntry(claim, initialReserve,
                "Initial reserve: min(claimed, sum insured) - deductible", null, now));
        outbox.append(ClaimEventType.CLAIM_SUBMITTED, claim, user.id(), Map.of("priority", priority.name()));
        return new SubmitResult(claim.getId(), true);
    }

    public void respondToInformation(CurrentUser user, UUID claimId, UUID requestId,
                                      ClaimRequests.RespondToInformation req) {
        Claim claim = load(claimId, user);
        permissions.require(ClaimAction.RESPOND_TO_INFO, claim, user);
        InformationRequest ir = infoRequests.findByIdAndClaimId(requestId, claimId)
                .orElseThrow(() -> ApiException.notFound("REQUEST_NOT_FOUND", "Information request not found"));
        Instant now = clock.instant();
        ir.answer(req.responseText(), now);
        infoRequests.flush();

        // Back to review only once nothing is outstanding.
        if (!infoRequests.existsByClaimIdAndStatus(claimId, InformationRequest.Status.OPEN)) {
            recordChange(claim, claim.informationReceived(now), user, "All requested information received", now);
        }
        outbox.append(ClaimEventType.INFORMATION_PROVIDED, claim, user.id(), Map.of("requestId", requestId));
    }

    public void withdraw(CurrentUser user, UUID claimId, ClaimRequests.Withdraw req) {
        Claim claim = load(claimId, user);
        permissions.require(ClaimAction.WITHDRAW, claim, user);
        Instant now = clock.instant();
        BigDecimal before = claim.getCurrentReserve();
        recordChange(claim, claim.withdraw(now), user, req.reason(), now);
        infoRequests.findByClaimIdOrderByRequestedAtAsc(claimId).forEach(InformationRequest::cancel);
        releaseReserve(claim, before, "Released on withdrawal", user, now);
        outbox.append(ClaimEventType.CLAIM_WITHDRAWN, claim, user.id(), Map.of());
    }

    // ------------------------------------------------------------------ staff

    public void assign(CurrentUser user, UUID claimId, ClaimRequests.Assign req) {
        Claim claim = load(claimId, user);
        UUID target;
        if (user.is(Role.HANDLER)) {
            permissions.require(ClaimAction.PICK_UP, claim, user);
            target = user.id();
        } else {
            permissions.require(ClaimAction.ASSIGN, claim, user);
            if (req == null || req.handlerId() == null) {
                throw ApiException.unprocessable("HANDLER_REQUIRED", "handlerId is required");
            }
            AppUser handler = users.findById(req.handlerId())
                    .filter(u -> u.getRole() == Role.HANDLER && claim.getTeamId().equals(u.getTeamId()))
                    .orElseThrow(() -> ApiException.unprocessable("INVALID_ASSIGNEE",
                            "Assignee must be a handler in the claim's team"));
            target = handler.getId();
        }
        UUID previous = claim.getAssignedHandlerId();
        Instant now = clock.instant();
        recordChange(claim, claim.assignTo(target, now), user, null, now);

        Map<String, Object> data = new HashMap<>();
        data.put("handlerId", target);
        data.put("previousHandlerId", previous);
        outbox.append(ClaimEventType.CLAIM_ASSIGNED, claim, user.id(), data);
    }

    public void requestInformation(CurrentUser user, UUID claimId, ClaimRequests.RequestInformation req) {
        Claim claim = load(claimId, user);
        permissions.require(ClaimAction.REQUEST_INFO, claim, user);
        Instant now = clock.instant();
        InformationRequest ir = infoRequests.save(
                new InformationRequest(claimId, user.id(), req.question(), req.dueAt(), now));
        recordChange(claim, claim.awaitInformation(now), user, req.question(), now);
        outbox.append(ClaimEventType.INFORMATION_REQUESTED, claim, user.id(),
                Map.of("requestId", ir.getId(), "question", req.question()));
    }

    public void setReserve(CurrentUser user, UUID claimId, ClaimRequests.SetReserve req) {
        Claim claim = load(claimId, user);
        permissions.require(ClaimAction.SET_RESERVE, claim, user);
        Policy policy = policyOf(claim);
        if (req.amount().compareTo(policy.getSumInsured()) > 0) {
            throw ApiException.unprocessable("RESERVE_EXCEEDS_SUM_INSURED", "Reserve cannot exceed the sum insured");
        }
        Instant now = clock.instant();
        BigDecimal before = claim.getCurrentReserve();
        claim.changeReserve(req.amount(), now);
        reserves.save(new ReserveEntry(claim, req.amount(), req.reason(), user.id(), now));
        outbox.append(ClaimEventType.RESERVE_CHANGED, claim, user.id(),
                Map.of("previousReserve", before, "reason", req.reason()));
    }

    public void approve(CurrentUser user, UUID claimId, ClaimRequests.Approve req) {
        Claim claim = load(claimId, user);
        permissions.require(ClaimAction.APPROVE, claim, user);
        Policy policy = policyOf(claim);
        if (req.amount().compareTo(policy.getSumInsured()) > 0) {
            throw ApiException.unprocessable("AMOUNT_EXCEEDS_SUM_INSURED", "Approved amount exceeds the sum insured");
        }
        checkAuthority(user, req.amount());

        Instant now = clock.instant();
        recordChange(claim, claim.approve(req.amount(), now), user, req.notes(), now);
        decisions.save(Decision.approved(claimId, req.amount(), req.notes(), user.id(), now));
        reserves.save(new ReserveEntry(claim, req.amount(), "Set to approved amount", user.id(), now));
        outbox.append(ClaimEventType.CLAIM_APPROVED, claim, user.id(), Map.of("approvedAmount", req.amount()));
    }

    public void reject(CurrentUser user, UUID claimId, ClaimRequests.Reject req) {
        Claim claim = load(claimId, user);
        permissions.require(ClaimAction.REJECT, claim, user);
        Instant now = clock.instant();
        BigDecimal before = claim.getCurrentReserve();
        recordChange(claim, claim.reject(now), user, req.reasonCode() + (req.notes() == null ? "" : ": " + req.notes()), now);
        decisions.save(Decision.rejected(claimId, req.reasonCode(), req.notes(), user.id(), now));
        releaseReserve(claim, before, "Released on rejection", user, now);
        outbox.append(ClaimEventType.CLAIM_REJECTED, claim, user.id(), Map.of("reasonCode", req.reasonCode().name()));
    }

    public void settle(CurrentUser user, UUID claimId, ClaimRequests.Settle req) {
        Claim claim = load(claimId, user);
        permissions.require(ClaimAction.SETTLE, claim, user);
        BigDecimal amount = decisions.findById(claimId)
                .map(Decision::getApprovedAmount)
                .orElseThrow(() -> new IllegalStateException("Approved claim without a decision: " + claimId));
        Instant now = clock.instant();
        payments.save(new Payment(claim, amount, req.paymentReference(), now));
        BigDecimal before = claim.getCurrentReserve();
        recordChange(claim, claim.settle(now), user, "Payment " + req.paymentReference(), now);
        releaseReserve(claim, before, "Released on settlement", user, now);
        outbox.append(ClaimEventType.CLAIM_SETTLED, claim, user.id(),
                Map.of("paidAmount", amount, "paymentReference", req.paymentReference()));
    }

    // ------------------------------------------------------------------ helpers

    /** Claims the caller can't see are reported as not found, so their existence isn't leaked. */
    private Claim load(UUID claimId, CurrentUser user) {
        return claims.findById(claimId)
                .filter(c -> permissions.canView(c, user))
                .orElseThrow(() -> ApiException.notFound("CLAIM_NOT_FOUND", "Claim not found"));
    }

    private Policy policyOf(Claim claim) {
        return policies.findById(claim.getPolicyId())
                .orElseThrow(() -> new IllegalStateException("Claim without policy: " + claim.getId()));
    }

    /** Handlers can only approve up to their authority limit; above it the manager must approve. */
    private void checkAuthority(CurrentUser user, BigDecimal amount) {
        if (!user.is(Role.HANDLER)) {
            return;
        }
        // null limit = unlimited (as documented on AppUser)
        BigDecimal limit = users.findById(user.id()).map(AppUser::getAuthorityLimit).orElse(null);
        if (limit != null && amount.compareTo(limit) > 0) {
            throw ApiException.unprocessable("AUTHORITY_LIMIT_EXCEEDED",
                    "Amount exceeds your authority limit of " + limit + "; refer to a manager");
        }
    }

    private void recordChange(Claim claim, StatusChange change, CurrentUser user, String reason, Instant now) {
        if (change != null) {
            history.save(new ClaimStatusHistory(claim.getId(), change.from(), change.to(), user.id(), reason, now));
        }
    }

    private void releaseReserve(Claim claim, BigDecimal before, String reason, CurrentUser user, Instant now) {
        if (before.signum() > 0) {
            reserves.save(new ReserveEntry(claim, BigDecimal.ZERO, reason, user.id(), now));
        }
    }
}
