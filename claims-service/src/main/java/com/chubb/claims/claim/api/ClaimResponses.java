package com.chubb.claims.claim.api;

import com.chubb.claims.claim.domain.ClaimAction;
import com.chubb.claims.claim.domain.ClaimStatus;
import com.chubb.claims.claim.domain.Priority;
import com.chubb.claims.reference.ProductLine;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Response shapes. Internal fields (reserve, SLA, reserve history) are null for claimants
 * and omitted from the JSON: a claimant never sees the insurer's reserve.
 */
public final class ClaimResponses {

    private ClaimResponses() {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ClaimSummary(
            UUID id,
            String claimNumber,
            ClaimStatus status,
            ProductLine productLine,
            String marketCode,
            String currency,
            BigDecimal claimedAmount,
            Instant reportedAt,
            UUID assignedHandlerId,
            String assignedHandlerName,
            String claimantName,
            // staff only
            BigDecimal currentReserve,
            Priority priority,
            Instant slaDueAt,
            Boolean slaBreached) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ClaimDetail(
            ClaimSummary summary,
            long version,
            UUID policyId,
            String policyNumber,
            LocalDate incidentDate,
            String description,
            Map<String, Object> details,
            List<TimelineItem> timeline,
            List<InformationRequestItem> informationRequests,
            DecisionItem decision,
            List<PaymentItem> payments,
            List<ClaimAction> allowedActions,
            // staff only
            List<ReserveItem> reserveHistory) {}

    public record TimelineItem(ClaimStatus fromStatus, ClaimStatus toStatus, String actorName, String reason,
                               Instant occurredAt) {}

    public record InformationRequestItem(UUID id, String question, String status, Instant requestedAt,
                                         Instant dueAt, String responseText, Instant respondedAt) {}

    public record DecisionItem(String outcome, BigDecimal approvedAmount, String reasonCode, String notes,
                               Instant decidedAt) {}

    public record PaymentItem(BigDecimal amount, String currency, String reference, Instant paidAt) {}

    public record ReserveItem(BigDecimal amount, String currency, String reason, String setByName, Instant setAt) {}
}
