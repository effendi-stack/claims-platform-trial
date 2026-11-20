package com.chubb.claims.claim.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InformationRequestRepository extends JpaRepository<InformationRequest, UUID> {

    List<InformationRequest> findByClaimIdOrderByRequestedAtAsc(UUID claimId);

    Optional<InformationRequest> findByIdAndClaimId(UUID id, UUID claimId);

    boolean existsByClaimIdAndStatus(UUID claimId, InformationRequest.Status status);
}
