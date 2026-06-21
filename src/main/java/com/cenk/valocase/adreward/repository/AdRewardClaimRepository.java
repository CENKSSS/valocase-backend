package com.cenk.valocase.adreward.repository;

import java.time.Instant;
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

    Optional<AdRewardClaim> findTopByAccountIdAndRewardTypeOrderByCreatedAtDesc(
            UUID accountId, AdRewardType rewardType);

    int countByAccountIdAndRewardTypeAndCreatedAtGreaterThanEqual(
            UUID accountId, AdRewardType rewardType, Instant since);

    Optional<AdRewardClaim> findTopByAccountIdAndRewardTypeAndConsumedFalseOrderByCreatedAtDesc(
            UUID accountId, AdRewardType rewardType);

    boolean existsByAccountIdAndRewardTypeAndSourceRef(
            UUID accountId, AdRewardType rewardType, String sourceRef);

    /** Locks the account's active upgrade buff so consume serializes with concurrent upgrades. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from AdRewardClaim c where c.accountId = :accountId "
            + "and c.rewardType = com.cenk.valocase.adreward.domain.AdRewardType.UPGRADE_PLUS_5 "
            + "and c.consumed = false order by c.createdAt desc")
    java.util.List<AdRewardClaim> findActiveUpgradeBuffsForUpdate(@Param("accountId") UUID accountId);
}
