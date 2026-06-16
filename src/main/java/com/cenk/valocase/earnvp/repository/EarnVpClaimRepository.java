package com.cenk.valocase.earnvp.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.earnvp.domain.EarnVpClaim;

public interface EarnVpClaimRepository extends JpaRepository<EarnVpClaim, UUID> {

    Optional<EarnVpClaim> findByAccountIdAndClientSessionId(UUID accountId, String clientSessionId);

    Optional<EarnVpClaim> findTopByAccountIdOrderByCreatedAtDesc(UUID accountId);
}
