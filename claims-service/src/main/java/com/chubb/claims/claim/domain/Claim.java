package com.chubb.claims.claim.domain;

import com.chubb.claims.common.BaseEntity;
import com.chubb.claims.reference.Policy;
import com.chubb.claims.reference.ProductLine;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The claim aggregate root. Every state change goes through a method here, and each
 * method enforces the transition rules in {@link ClaimStatus}. Other aggregates
 * (policy, users) are referenced by id only, never by object, to keep boundaries explicit.
 *
 * Who is allowed to call each method is decided by ClaimPermissions, not here.
 */
@Entity
@Table(name = "claim")
public class Claim extends BaseEntity {

    @Id
    private UUID id;
    private String claimNumber;
    private UUID policyId;
    private UUID claimantId;

    @Enumerated(EnumType.STRING)
    private ProductLine productLine;
    private String marketCode;
    private String currency;

    @Enumerated(EnumType.STRING)
    private ClaimStatus status;

    private LocalDate incidentDate;
    private Instant reportedAt;
    private String description;
    private BigDecimal claimedAmount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> details;

    private UUID teamId;
    private UUID assignedHandlerId;

    @Enumerated(EnumType.STRING)
    private Priority priority;

    private Instant slaDueAt;
    private Instant slaPausedAt;
    private String submissionKey;
    private BigDecimal currentReserve;

    @Version
    private Long version;

    private Instant createdAt;
    private Instant updatedAt;

    protected Claim() {
    }

    /** First notice of loss. Starts in SUBMITTED, routed to the team for the policy's market. */
    public static Claim submit(UUID id, String claimNumber, Policy policy, UUID claimantId, UUID teamId,
                               LocalDate incidentDate, String description, BigDecimal claimedAmount,
                               Map<String, Object> details, Priority priority, BigDecimal initialReserve,
                               String submissionKey, Instant now) {
        Claim c = new Claim();
        c.id = id;
        c.claimNumber = claimNumber;
        c.policyId = policy.getId();
        c.claimantId = claimantId;
        c.productLine = policy.getProductLine();
        c.marketCode = policy.getMarketCode();
        c.currency = policy.getCurrency();
        c.status = ClaimStatus.SUBMITTED;
        c.incidentDate = incidentDate;
        c.reportedAt = now;
        c.description = description;
        c.claimedAmount = claimedAmount;
        c.details = details;
        c.teamId = teamId;
        c.priority = priority;
        c.slaDueAt = now.plus(priority.decisionSla());
        c.currentReserve = initialReserve;
        c.submissionKey = submissionKey;
        c.createdAt = now;
        c.updatedAt = now;
        return c;
    }

    // ------------------------------------------------------------------ behaviour

    /**
     * Pick up (SUBMITTED -> IN_REVIEW) or reassign (stays IN_REVIEW / AWAITING_INFO).
     * @return the status change, or null if the status did not change
     */
    public StatusChange assignTo(UUID handlerId, Instant now) {
        Objects.requireNonNull(handlerId);
        StatusChange change = null;
        if (status == ClaimStatus.SUBMITTED) {
            change = moveTo(ClaimStatus.IN_REVIEW, now);
        } else if (!status.isOpen()) {
            throw new InvalidTransitionException(status, status);
        }
        this.assignedHandlerId = handlerId;
        touch(now);
        return change;
    }

    /** Asking the claimant for more information pauses the SLA clock. */
    public StatusChange awaitInformation(Instant now) {
        if (status == ClaimStatus.AWAITING_INFO) {
            return null; // another request while one is already outstanding
        }
        StatusChange change = moveTo(ClaimStatus.AWAITING_INFO, now);
        this.slaPausedAt = now;
        return change;
    }

    /** All outstanding requests answered: back to review, and the SLA is extended by the paused time. */
    public StatusChange informationReceived(Instant now) {
        StatusChange change = moveTo(ClaimStatus.IN_REVIEW, now);
        resumeSla(now);
        return change;
    }

    public void changeReserve(BigDecimal amount, Instant now) {
        if (status.isTerminal()) {
            throw new InvalidTransitionException(status, status);
        }
        this.currentReserve = amount;
        touch(now);
    }

    /** Approval fixes the expected payout: the reserve becomes the approved amount. */
    public StatusChange approve(BigDecimal approvedAmount, Instant now) {
        StatusChange change = moveTo(ClaimStatus.APPROVED, now);
        this.currentReserve = approvedAmount;
        return change;
    }

    public StatusChange reject(Instant now) {
        StatusChange change = moveTo(ClaimStatus.REJECTED, now);
        this.currentReserve = BigDecimal.ZERO;
        return change;
    }

    /** Payment made: liability is no longer outstanding. */
    public StatusChange settle(Instant now) {
        StatusChange change = moveTo(ClaimStatus.SETTLED, now);
        this.currentReserve = BigDecimal.ZERO;
        return change;
    }

    public StatusChange withdraw(Instant now) {
        StatusChange change = moveTo(ClaimStatus.WITHDRAWN, now);
        this.currentReserve = BigDecimal.ZERO;
        resumeSla(now);
        return change;
    }

    /** SLA breached if the due time has passed (measured at the pause point while awaiting info). */
    public boolean isSlaBreached(Instant now) {
        if (!status.isOpen()) {
            return false;
        }
        Instant effectiveNow = slaPausedAt != null ? slaPausedAt : now;
        return effectiveNow.isAfter(slaDueAt);
    }

    private StatusChange moveTo(ClaimStatus target, Instant now) {
        if (!status.canTransitionTo(target)) {
            throw new InvalidTransitionException(status, target);
        }
        StatusChange change = new StatusChange(status, target);
        this.status = target;
        touch(now);
        return change;
    }

    private void resumeSla(Instant now) {
        if (slaPausedAt != null) {
            slaDueAt = slaDueAt.plus(Duration.between(slaPausedAt, now));
            slaPausedAt = null;
        }
    }

    private void touch(Instant now) {
        this.updatedAt = now;
    }

    public record StatusChange(ClaimStatus from, ClaimStatus to) {}

    // ------------------------------------------------------------------ accessors

    @Override
    public UUID getId() { return id; }
    public String getClaimNumber() { return claimNumber; }
    public UUID getPolicyId() { return policyId; }
    public UUID getClaimantId() { return claimantId; }
    public ProductLine getProductLine() { return productLine; }
    public String getMarketCode() { return marketCode; }
    public String getCurrency() { return currency; }
    public ClaimStatus getStatus() { return status; }
    public LocalDate getIncidentDate() { return incidentDate; }
    public Instant getReportedAt() { return reportedAt; }
    public String getDescription() { return description; }
    public BigDecimal getClaimedAmount() { return claimedAmount; }
    public Map<String, Object> getDetails() { return details; }
    public UUID getTeamId() { return teamId; }
    public UUID getAssignedHandlerId() { return assignedHandlerId; }
    public Priority getPriority() { return priority; }
    public Instant getSlaDueAt() { return slaDueAt; }
    public Instant getSlaPausedAt() { return slaPausedAt; }
    public BigDecimal getCurrentReserve() { return currentReserve; }
    public Long getVersion() { return version; }
    public Instant getUpdatedAt() { return updatedAt; }
}
