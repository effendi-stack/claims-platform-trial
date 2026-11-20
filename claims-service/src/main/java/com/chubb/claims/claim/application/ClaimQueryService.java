package com.chubb.claims.claim.application;

import com.chubb.claims.claim.api.ClaimResponses.ClaimDetail;
import com.chubb.claims.claim.api.ClaimResponses.ClaimSummary;
import com.chubb.claims.claim.api.ClaimResponses.DecisionItem;
import com.chubb.claims.claim.api.ClaimResponses.InformationRequestItem;
import com.chubb.claims.claim.api.ClaimResponses.PaymentItem;
import com.chubb.claims.claim.api.ClaimResponses.ReserveItem;
import com.chubb.claims.claim.api.ClaimResponses.TimelineItem;
import com.chubb.claims.claim.domain.Claim;
import com.chubb.claims.claim.domain.ClaimPermissions;
import com.chubb.claims.claim.domain.ClaimRepository;
import com.chubb.claims.claim.domain.ClaimStatus;
import com.chubb.claims.claim.domain.ClaimStatusHistory;
import com.chubb.claims.claim.domain.ClaimStatusHistoryRepository;
import com.chubb.claims.claim.domain.DecisionRepository;
import com.chubb.claims.claim.domain.InformationRequestRepository;
import com.chubb.claims.claim.domain.PaymentRepository;
import com.chubb.claims.claim.domain.ReserveEntry;
import com.chubb.claims.claim.domain.ReserveEntryRepository;
import com.chubb.claims.common.ApiException;
import com.chubb.claims.common.PageResponse;
import com.chubb.claims.reference.AppUser;
import com.chubb.claims.reference.AppUserRepository;
import com.chubb.claims.reference.PolicyRepository;
import com.chubb.claims.reference.ProductLine;
import com.chubb.claims.reference.Role;
import com.chubb.claims.security.CurrentUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read side of claims-service: queues, lists and the claim detail view. */
@Service
@Transactional(readOnly = true)
public class ClaimQueryService {

    private final ClaimRepository claims;
    private final ClaimStatusHistoryRepository history;
    private final InformationRequestRepository infoRequests;
    private final ReserveEntryRepository reserves;
    private final DecisionRepository decisions;
    private final PaymentRepository payments;
    private final PolicyRepository policies;
    private final AppUserRepository users;
    private final ClaimPermissions permissions;
    private final Clock clock;

    public ClaimQueryService(ClaimRepository claims, ClaimStatusHistoryRepository history,
                             InformationRequestRepository infoRequests, ReserveEntryRepository reserves,
                             DecisionRepository decisions, PaymentRepository payments, PolicyRepository policies,
                             AppUserRepository users, ClaimPermissions permissions, Clock clock) {
        this.claims = claims;
        this.history = history;
        this.infoRequests = infoRequests;
        this.reserves = reserves;
        this.decisions = decisions;
        this.payments = payments;
        this.policies = policies;
        this.users = users;
        this.permissions = permissions;
        this.clock = clock;
    }

    /**
     * @param assignedTo "me", "unassigned", or a handler id
     */
    public record ClaimFilter(List<ClaimStatus> status, String assignedTo, String marketCode,
                              ProductLine productLine) {}

    public PageResponse<ClaimSummary> list(CurrentUser user, ClaimFilter filter, Pageable pageable) {
        Specification<Claim> spec = Specification.allOf(
                ClaimSpecifications.visibleTo(user),
                ClaimSpecifications.statusIn(filter.status()),
                ClaimSpecifications.assignedTo(filter.assignedTo(), user),
                ClaimSpecifications.market(filter.marketCode()),
                ClaimSpecifications.line(filter.productLine()));

        Page<Claim> page = claims.findAll(spec, withDefaultSort(pageable, user));
        Map<UUID, AppUser> people = usersById(page.getContent().stream()
                .flatMap(c -> java.util.stream.Stream.of(c.getAssignedHandlerId(), c.getClaimantId()))
                .toList());
        Instant now = clock.instant();
        return PageResponse.of(page.map(c -> summary(c, user, people, now)));
    }

    public ClaimDetail get(CurrentUser user, UUID claimId) {
        Claim c = claims.findById(claimId)
                .filter(x -> permissions.canView(x, user))
                .orElseThrow(() -> ApiException.notFound("CLAIM_NOT_FOUND", "Claim not found"));
        boolean staff = !user.is(Role.CLAIMANT);

        List<ClaimStatusHistory> timeline = history.findByClaimIdOrderByOccurredAtAsc(claimId);
        List<ReserveEntry> reserveRows = staff ? reserves.findByClaimIdOrderBySetAtAsc(claimId) : List.of();

        Set<UUID> peopleIds = new HashSet<>();
        peopleIds.add(c.getAssignedHandlerId());
        peopleIds.add(c.getClaimantId());
        timeline.forEach(h -> peopleIds.add(h.getActorId()));
        reserveRows.forEach(r -> peopleIds.add(r.getSetBy()));
        Map<UUID, AppUser> people = usersById(peopleIds);

        String policyNumber = policies.findById(c.getPolicyId()).map(p -> p.getPolicyNumber()).orElse(null);

        return new ClaimDetail(
                summary(c, user, people, clock.instant()),
                c.getVersion(),
                c.getPolicyId(),
                policyNumber,
                c.getIncidentDate(),
                c.getDescription(),
                c.getDetails(),
                timeline.stream().map(h -> new TimelineItem(h.getFromStatus(), h.getToStatus(),
                        nameOf(people, h.getActorId()), h.getReason(), h.getOccurredAt())).toList(),
                infoRequests.findByClaimIdOrderByRequestedAtAsc(claimId).stream()
                        .map(r -> new InformationRequestItem(r.getId(), r.getQuestion(), r.getStatus().name(),
                                r.getRequestedAt(), r.getDueAt(), r.getResponseText(), r.getRespondedAt()))
                        .toList(),
                decisions.findById(claimId).map(d -> new DecisionItem(d.getOutcome().name(), d.getApprovedAmount(),
                        d.getReasonCode(), d.getNotes(), d.getDecidedAt())).orElse(null),
                payments.findByClaimIdOrderByPaidAtAsc(claimId).stream()
                        .map(p -> new PaymentItem(p.getAmount(), p.getCurrency(), p.getReference(), p.getPaidAt()))
                        .toList(),
                permissions.allowedActions(c, user),
                staff ? reserveRows.stream().map(r -> new ReserveItem(r.getAmount(), r.getCurrency(), r.getReason(),
                        r.getSetBy() == null ? "System" : nameOf(people, r.getSetBy()), r.getSetAt())).toList() : null);
    }

    private ClaimSummary summary(Claim c, CurrentUser user, Map<UUID, AppUser> people, Instant now) {
        boolean staff = !user.is(Role.CLAIMANT);
        return new ClaimSummary(
                c.getId(), c.getClaimNumber(), c.getStatus(), c.getProductLine(), c.getMarketCode(), c.getCurrency(),
                c.getClaimedAmount(), c.getReportedAt(), c.getAssignedHandlerId(),
                nameOf(people, c.getAssignedHandlerId()),
                nameOf(people, c.getClaimantId()),
                staff ? c.getCurrentReserve() : null,
                staff ? c.getPriority() : null,
                staff ? c.getSlaDueAt() : null,
                staff ? c.isSlaBreached(now) : null);
    }

    /** Staff work oldest-deadline-first; claimants see their newest claims first. */
    private static Pageable withDefaultSort(Pageable pageable, CurrentUser user) {
        if (pageable.getSort().isSorted()) {
            return pageable;
        }
        Sort sort = user.is(Role.CLAIMANT) ? Sort.by(Sort.Direction.DESC, "reportedAt")
                : Sort.by(Sort.Direction.ASC, "slaDueAt");
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }

    private Map<UUID, AppUser> usersById(Collection<UUID> ids) {
        List<UUID> nonNull = ids.stream().filter(Objects::nonNull).distinct().collect(Collectors.toCollection(ArrayList::new));
        return users.findAllById(nonNull).stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
    }

    private static String nameOf(Map<UUID, AppUser> people, UUID id) {
        AppUser u = id == null ? null : people.get(id);
        return u == null ? null : u.getName();
    }
}
