package com.chubb.claims.claim.domain;

import com.chubb.claims.reference.Policy;
import com.chubb.claims.reference.ProductLine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClaimTest {

    private static final Instant T0 = Instant.parse("2026-11-01T00:00:00Z");
    private final UUID handler = UUID.randomUUID();
    private Claim claim;

    @BeforeEach
    void setUp() {
        Policy policy = mock(Policy.class);
        when(policy.getId()).thenReturn(UUID.randomUUID());
        when(policy.getProductLine()).thenReturn(ProductLine.MOTOR);
        when(policy.getMarketCode()).thenReturn("SG");
        when(policy.getCurrency()).thenReturn("SGD");
        claim = Claim.submit(UUID.randomUUID(), "SG-MOT-2026-001000", policy, UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.parse("2026-10-30"), "Rear-ended at traffic lights", new BigDecimal("5000.00"), Map.of(),
                Priority.NORMAL, new BigDecimal("4500.00"), null, T0);
    }

    @Test
    void newClaimIsSubmittedWithSlaFromPriority() {
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        assertThat(claim.getSlaDueAt()).isEqualTo(T0.plus(Duration.ofDays(10)));
        assertThat(claim.getCurrentReserve()).isEqualByComparingTo("4500");
    }

    @Test
    void pickingUpMovesToReviewButReassigningDoesNot() {
        assertThat(claim.assignTo(handler, T0)).isEqualTo(new Claim.StatusChange(ClaimStatus.SUBMITTED, ClaimStatus.IN_REVIEW));
        assertThat(claim.assignTo(UUID.randomUUID(), T0)).isNull();
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.IN_REVIEW);
    }

    @Test
    void awaitingInformationPausesTheSlaAndExtendsItByThePausedTime() {
        claim.assignTo(handler, T0);
        claim.awaitInformation(T0.plus(Duration.ofDays(2)));
        // 3 days pass while waiting on the claimant
        claim.informationReceived(T0.plus(Duration.ofDays(5)));

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.IN_REVIEW);
        assertThat(claim.getSlaPausedAt()).isNull();
        assertThat(claim.getSlaDueAt()).isEqualTo(T0.plus(Duration.ofDays(13)));
    }

    @Test
    void slaIsNotBreachedWhileWaitingOnTheClaimant() {
        claim.assignTo(handler, T0);
        claim.awaitInformation(T0.plus(Duration.ofDays(1)));
        assertThat(claim.isSlaBreached(T0.plus(Duration.ofDays(30)))).isFalse();
    }

    @Test
    void approveThenSettleMovesReserveToApprovedAmountThenZero() {
        claim.assignTo(handler, T0);
        claim.approve(new BigDecimal("4000.00"), T0);
        assertThat(claim.getCurrentReserve()).isEqualByComparingTo("4000");
        claim.settle(T0);
        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SETTLED);
        assertThat(claim.getCurrentReserve()).isEqualByComparingTo("0");
    }

    @Test
    void cannotApproveAnUnreviewedClaim() {
        assertThatThrownBy(() -> claim.approve(BigDecimal.TEN, T0))
                .isInstanceOf(InvalidTransitionException.class)
                .hasMessageContaining("SUBMITTED to APPROVED");
    }

    @Test
    void cannotChangeReserveOnAClosedClaim() {
        claim.withdraw(T0);
        assertThatThrownBy(() -> claim.changeReserve(BigDecimal.ONE, T0)).isInstanceOf(InvalidTransitionException.class);
    }
}
