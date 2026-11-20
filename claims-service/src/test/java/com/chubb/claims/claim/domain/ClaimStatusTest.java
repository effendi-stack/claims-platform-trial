package com.chubb.claims.claim.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static com.chubb.claims.claim.domain.ClaimStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

class ClaimStatusTest {

    @Test
    void lifecycleTransitionsMatchTheDesign() {
        assertThat(SUBMITTED.allowedNext()).containsExactlyInAnyOrder(IN_REVIEW, WITHDRAWN);
        assertThat(IN_REVIEW.allowedNext()).containsExactlyInAnyOrder(AWAITING_INFO, APPROVED, REJECTED, WITHDRAWN);
        assertThat(AWAITING_INFO.allowedNext()).containsExactlyInAnyOrder(IN_REVIEW, WITHDRAWN);
        assertThat(APPROVED.allowedNext()).containsExactly(SETTLED);
    }

    @ParameterizedTest
    @EnumSource(value = ClaimStatus.class, names = {"SETTLED", "REJECTED", "WITHDRAWN"})
    void terminalStatesAllowNothing(ClaimStatus s) {
        assertThat(s.isTerminal()).isTrue();
        assertThat(s.isOpen()).isFalse();
    }

    @Test
    void cannotSkipReviewOrUnsettle() {
        assertThat(SUBMITTED.canTransitionTo(APPROVED)).isFalse();
        assertThat(AWAITING_INFO.canTransitionTo(APPROVED)).isFalse();
        assertThat(SETTLED.canTransitionTo(APPROVED)).isFalse();
        assertThat(APPROVED.canTransitionTo(WITHDRAWN)).isFalse();
    }
}
