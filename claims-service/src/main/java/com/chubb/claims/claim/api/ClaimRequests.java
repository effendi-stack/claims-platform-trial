package com.chubb.claims.claim.api;

import com.chubb.claims.claim.domain.RejectionReason;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Request bodies for claim commands. Structural validation here; business rules in the service. */
public final class ClaimRequests {

    private ClaimRequests() {
    }

    public enum Peril { FIRE, FLOOD, STORM, THEFT, ESCAPE_OF_WATER, ACCIDENTAL_DAMAGE, OTHER }

    public record MotorDetails(
            @NotBlank @Size(max = 20) String vehicleRegistration,
            @NotBlank @Size(max = 300) String location,
            @NotNull Boolean thirdPartyInvolved,
            @NotNull Boolean injuries,
            @Size(max = 50) String policeReportNumber) {}

    public record PropertyDetails(
            @NotBlank @Size(max = 300) String propertyAddress,
            @NotNull Peril peril,
            @NotNull Boolean habitable) {}

    /** Exactly one of motor / property must be present, matching the policy's product line. */
    public record SubmitClaim(
            @NotNull UUID policyId,
            @NotNull @PastOrPresent LocalDate incidentDate,
            @NotBlank @Size(max = 4000) String description,
            @NotNull @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal claimedAmount,
            @Valid MotorDetails motor,
            @Valid PropertyDetails property) {}

    /** handlerId omitted = a handler picking the claim up themselves. */
    public record Assign(UUID handlerId) {}

    public record RequestInformation(@NotBlank @Size(max = 2000) String question, Instant dueAt) {}

    public record RespondToInformation(@NotBlank @Size(max = 4000) String responseText) {}

    public record SetReserve(
            @NotNull @PositiveOrZero @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 500) String reason) {}

    public record Approve(
            @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount,
            @Size(max = 2000) String notes) {}

    public record Reject(@NotNull RejectionReason reasonCode, @Size(max = 2000) String notes) {}

    public record Settle(@NotBlank @Size(max = 100) String paymentReference) {}

    public record Withdraw(@Size(max = 500) String reason) {}
}
