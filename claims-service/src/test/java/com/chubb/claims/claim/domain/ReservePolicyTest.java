package com.chubb.claims.claim.domain;

import com.chubb.claims.reference.Policy;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReservePolicyTest {

    @ParameterizedTest(name = "claimed {0}, sum insured 80000, deductible 500 -> {1}")
    @CsvSource({
            "12000, 11500.00",   // normal case
            "100000, 79500.00",  // capped at sum insured
            "300, 0.00",         // below deductible -> never negative
            "500, 0.00"})
    void initialReserveIsClaimCappedAtSumInsuredLessDeductible(String claimed, String expected) {
        Policy policy = mock(Policy.class);
        when(policy.getSumInsured()).thenReturn(new BigDecimal("80000.00"));
        when(policy.getDeductible()).thenReturn(new BigDecimal("500.00"));

        assertThat(ReservePolicy.initialReserve(new BigDecimal(claimed), policy)).isEqualTo(new BigDecimal(expected));
    }
}
