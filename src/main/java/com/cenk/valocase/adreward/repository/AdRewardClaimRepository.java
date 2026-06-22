package com.cenk.valocase.adreward.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.cenk.valocase.adreward.domain.AdRewardClaim;
import com.cenk.valocase.adreward.domain.AdRewardType;

public interface AdRewardClaimRepository extends JpaRepository<AdRewardClaim, UUID> {

    Optional<AdRewardClaim> findByAccountIdAndRewardTypeAndAdToken(
            UUID accountId, AdRewardType rewardType, String adToken);

    long countByAccountIdAndRewardType(UUID accountId, AdRewardType rewardType);

    Optional<AdRewardClaim> findFirstByAccountIdAndRewardTypeOrderByCreatedAtDesc(
            UUID accountId, AdRewardType rewardType);

    boolean existsByAccountIdAndRewardTypeAndSourceRef(
            UUID accountId, AdRewardType rewardType, String sourceRef);

    boolean existsByAccountIdAndRewardTypeAndConsumedFalse(
            UUID accountId, AdRewardType rewardType);

    Optional<AdRewardClaim> findByAccountIdAndRewardTypeAndSourceRefAndConsumedFalse(
            UUID accountId, AdRewardType rewardType, String sourceRef);

    /** Locks the unconsumed upgrade buff for a context so consume serializes with concurrent upgrades. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AdRewardClaim c where c.accountId = :accountId "
            + "and c.rewardType = com.cenk.valocase.adreward.domain.AdRewardType.UPGRADE_PLUS_5 "
            + "and c.sourceRef = :contextKey and c.consumed = false")
    List<AdRewardClaim> findActiveUpgradeBuffForContextForUpdate(
            @Param("accountId") UUID accountId, @Param("contextKey") String contextKey);
}
