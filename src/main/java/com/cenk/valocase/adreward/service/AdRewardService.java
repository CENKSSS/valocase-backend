package com.cenk.valocase.adreward.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.adreward.domain.AdRewardClaim;
import com.cenk.valocase.adreward.domain.AdRewardType;
import com.cenk.valocase.adreward.dto.AdRewardClaimResponse;
import com.cenk.valocase.adreward.dto.AdRewardStatusResponse;
import com.cenk.valocase.adreward.dto.AdRewardTypeStatus;
import com.cenk.valocase.adreward.dto.UpgradeBuffStateResponse;
import com.cenk.valocase.adreward.repository.AdRewardClaimRepository;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.earnvp.domain.EarnVpClaim;
import com.cenk.valocase.earnvp.repository.EarnVpClaimRepository;
import com.cenk.valocase.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;

/**
 * Server-authoritative rewarded-ad rewards. The client never supplies the reward
 * amount, eligibility, or remaining count: EARN_VP_2X doubles the player's last
 * server-recorded Earn VP grant through the wallet, and UPGRADE_PLUS_5 issues a
 * single unconsumed upgrade-chance buff that the upgrade flow reads and consumes.
 * Limits and cooldowns come from {@link AdRewardPolicy}; the unique
 * (account, reward_type, ad_token) constraint blocks replaying the same ad.
 *
 * Ad-network verification is not integrated yet: {@code adToken} is accepted as-is
 * and is the seam where server-side ad validation will be added.
 */
@Service
@RequiredArgsConstructor
public class AdRewardService {

    public static final String REASON_EARN_VP_2X = "AD_REWARD_EARN_VP_2X";

    public static final String CODE_NOTHING_TO_DOUBLE = "AD_REWARD_NOTHING_TO_DOUBLE";
    public static final String CODE_ALREADY_DOUBLED = "AD_REWARD_ALREADY_DOUBLED";
    public static final String CODE_BUFF_ALREADY_ACTIVE = "AD_REWARD_BUFF_ALREADY_ACTIVE";
    public static final String CODE_ON_COOLDOWN = "AD_REWARD_ON_COOLDOWN";
    public static final String CODE_DAILY_LIMIT = "AD_REWARD_DAILY_LIMIT_REACHED";

    static final int MAX_AD_TOKEN_LENGTH = 100;

    private final AdRewardClaimRepository adRewardClaimRepository;
    private final EarnVpClaimRepository earnVpClaimRepository;
    private final WalletService walletService;
    private final AdRewardPolicy policy;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AdRewardStatusResponse getStatus(UUID accountId) {
        Instant now = Instant.now(clock);
        List<AdRewardTypeStatus> rewards = new ArrayList<>();
        for (AdRewardType type : AdRewardType.values()) {
            rewards.add(typeStatus(accountId, type, now));
        }
        return new AdRewardStatusResponse(rewards, upgradeBuffState(accountId));
    }

    @Transactional
    public AdRewardClaimResponse claim(UUID accountId, AdRewardType type, String rawAdToken) {
        String adToken = normalizeAdToken(rawAdToken);
        Instant now = Instant.now(clock);

        Optional<AdRewardClaim> existing =
                adRewardClaimRepository.findByAccountIdAndRewardTypeAndAdToken(accountId, type, adToken);
        if (existing.isPresent()) {
            return duplicateResponse(accountId, type, existing.get(), now);
        }

        enforceCooldown(accountId, type, now);
        enforceDailyLimit(accountId, type, now);

        return switch (type) {
            case EARN_VP_2X -> claimEarnVp2x(accountId, adToken, now);
            case UPGRADE_PLUS_5 -> claimUpgradeBuff(accountId, adToken, now);
        };
    }

    /** Buff size the active upgrade buff would add, without consuming it. Used by preview. */
    @Transactional(readOnly = true)
    public double peekActiveUpgradeBuffPercent(UUID accountId) {
        return adRewardClaimRepository
                .findTopByAccountIdAndRewardTypeAndConsumedFalseOrderByCreatedAtDesc(
                        accountId, AdRewardType.UPGRADE_PLUS_5)
                .map(AdRewardClaim::getBuffPercent)
                .orElse(0.0);
    }

    /** Marks the active upgrade buff consumed and returns its size; 0 when none. Joins the caller's transaction. */
    @Transactional
    public double consumeActiveUpgradeBuff(UUID accountId) {
        List<AdRewardClaim> active = adRewardClaimRepository.findActiveUpgradeBuffsForUpdate(accountId);
        if (active.isEmpty()) {
            return 0.0;
        }
        AdRewardClaim buff = active.get(0);
        buff.setConsumed(true);
        buff.setConsumedAt(Instant.now(clock));
        adRewardClaimRepository.save(buff);
        return buff.getBuffPercent() != null ? buff.getBuffPercent() : 0.0;
    }

    private AdRewardClaimResponse claimEarnVp2x(UUID accountId, String adToken, Instant now) {
        EarnVpClaim last = earnVpClaimRepository.findTopByAccountIdOrderByCreatedAtDesc(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "No recent Earn VP to double", CODE_NOTHING_TO_DOUBLE));

        long bonus = last.getVpGranted();
        if (bonus <= 0) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No recent Earn VP to double", CODE_NOTHING_TO_DOUBLE);
        }

        String sourceRef = last.getId().toString();
        if (adRewardClaimRepository.existsByAccountIdAndRewardTypeAndSourceRef(
                accountId, AdRewardType.EARN_VP_2X, sourceRef)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This Earn VP session was already doubled", CODE_ALREADY_DOUBLED);
        }

        AdRewardClaim claim = new AdRewardClaim();
        claim.setAccountId(accountId);
        claim.setRewardType(AdRewardType.EARN_VP_2X);
        claim.setAdToken(adToken);
        claim.setGrantedVp(bonus);
        claim.setConsumed(true);
        claim.setSourceRef(sourceRef);
        claim.setCreatedAt(now);
        claim.setConsumedAt(now);
        try {
            adRewardClaimRepository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException e) {
            AdRewardClaim raced = adRewardClaimRepository
                    .findByAccountIdAndRewardTypeAndAdToken(accountId, AdRewardType.EARN_VP_2X, adToken)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Duplicate ad reward claim"));
            return duplicateResponse(accountId, AdRewardType.EARN_VP_2X, raced, now);
        }

        long newBalance = walletService.credit(accountId, bonus, REASON_EARN_VP_2X, claim.getId()).getVpBalance();
        AdRewardTypeStatus status = typeStatus(accountId, AdRewardType.EARN_VP_2X, now);
        return new AdRewardClaimResponse(AdRewardType.EARN_VP_2X.name(), "OK", bonus, newBalance,
                null, status.remainingToday(), status.cooldownRemainingSeconds());
    }

    private AdRewardClaimResponse claimUpgradeBuff(UUID accountId, String adToken, Instant now) {
        if (peekActiveUpgradeBuffPercent(accountId) > 0.0) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "An upgrade buff is already active; use it before watching another ad",
                    CODE_BUFF_ALREADY_ACTIVE);
        }

        double buffPercent = policy.upgradeBuffPercent();
        AdRewardClaim claim = new AdRewardClaim();
        claim.setAccountId(accountId);
        claim.setRewardType(AdRewardType.UPGRADE_PLUS_5);
        claim.setAdToken(adToken);
        claim.setBuffPercent(buffPercent);
        claim.setConsumed(false);
        claim.setCreatedAt(now);
        try {
            adRewardClaimRepository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException e) {
            AdRewardClaim raced = adRewardClaimRepository
                    .findByAccountIdAndRewardTypeAndAdToken(accountId, AdRewardType.UPGRADE_PLUS_5, adToken)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Duplicate ad reward claim"));
            return duplicateResponse(accountId, AdRewardType.UPGRADE_PLUS_5, raced, now);
        }

        AdRewardTypeStatus status = typeStatus(accountId, AdRewardType.UPGRADE_PLUS_5, now);
        return new AdRewardClaimResponse(AdRewardType.UPGRADE_PLUS_5.name(), "OK", 0L, null,
                new UpgradeBuffStateResponse(true, buffPercent, now),
                status.remainingToday(), status.cooldownRemainingSeconds());
    }

    private AdRewardClaimResponse duplicateResponse(UUID accountId, AdRewardType type,
                                                    AdRewardClaim claim, Instant now) {
        AdRewardTypeStatus status = typeStatus(accountId, type, now);
        long vpGranted = claim.getGrantedVp() != null ? claim.getGrantedVp() : 0L;
        Long balance = type == AdRewardType.EARN_VP_2X
                ? walletService.getWalletForAccount(accountId).vpBalance() : null;
        UpgradeBuffStateResponse buff = type == AdRewardType.UPGRADE_PLUS_5
                ? upgradeBuffState(accountId) : null;
        return new AdRewardClaimResponse(type.name(), "DUPLICATE", vpGranted, balance, buff,
                status.remainingToday(), status.cooldownRemainingSeconds());
    }

    private AdRewardTypeStatus typeStatus(UUID accountId, AdRewardType type, Instant now) {
        int dailyLimit = policy.dailyLimit(type);
        int remaining = Math.max(0, dailyLimit - usedToday(accountId, type, now));
        long cooldownRemaining = cooldownRemaining(accountId, type, now);

        boolean available = remaining > 0 && cooldownRemaining == 0;
        if (type == AdRewardType.UPGRADE_PLUS_5 && peekActiveUpgradeBuffPercent(accountId) > 0.0) {
            available = false;
        }
        return new AdRewardTypeStatus(type.name(), available, remaining, dailyLimit, cooldownRemaining);
    }

    private UpgradeBuffStateResponse upgradeBuffState(UUID accountId) {
        return adRewardClaimRepository
                .findTopByAccountIdAndRewardTypeAndConsumedFalseOrderByCreatedAtDesc(
                        accountId, AdRewardType.UPGRADE_PLUS_5)
                .map(buff -> new UpgradeBuffStateResponse(
                        true,
                        buff.getBuffPercent() != null ? buff.getBuffPercent() : 0.0,
                        buff.getCreatedAt()))
                .orElseGet(UpgradeBuffStateResponse::inactive);
    }

    private int usedToday(UUID accountId, AdRewardType type, Instant now) {
        Instant dayStart = now.truncatedTo(ChronoUnit.DAYS);
        return adRewardClaimRepository
                .countByAccountIdAndRewardTypeAndCreatedAtGreaterThanEqual(accountId, type, dayStart);
    }

    private long cooldownRemaining(UUID accountId, AdRewardType type, Instant now) {
        return adRewardClaimRepository.findTopByAccountIdAndRewardTypeOrderByCreatedAtDesc(accountId, type)
                .map(last -> Math.max(0L,
                        policy.cooldownSeconds(type) - Duration.between(last.getCreatedAt(), now).getSeconds()))
                .orElse(0L);
    }

    private void enforceCooldown(UUID accountId, AdRewardType type, Instant now) {
        long remaining = cooldownRemaining(accountId, type, now);
        if (remaining > 0) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Ad reward on cooldown; " + remaining + "s remaining", CODE_ON_COOLDOWN);
        }
    }

    private void enforceDailyLimit(UUID accountId, AdRewardType type, Instant now) {
        if (usedToday(accountId, type, now) >= policy.dailyLimit(type)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Daily ad reward limit reached for " + type.name(), CODE_DAILY_LIMIT);
        }
    }

    private String normalizeAdToken(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "adToken is required");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_AD_TOKEN_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "adToken is too long");
        }
        return trimmed;
    }
}
