package com.chubb.claims.claim.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;
import java.util.UUID;

public interface ClaimRepository extends JpaRepository<Claim, UUID>, JpaSpecificationExecutor<Claim> {

    Optional<Claim> findByClaimantIdAndSubmissionKey(UUID claimantId, String submissionKey);
}
