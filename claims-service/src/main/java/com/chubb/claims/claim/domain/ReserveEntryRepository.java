package com.chubb.claims.claim.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReserveEntryRepository extends JpaRepository<ReserveEntry, UUID> {

    List<ReserveEntry> findByClaimIdOrderBySetAtAsc(UUID claimId);
}
