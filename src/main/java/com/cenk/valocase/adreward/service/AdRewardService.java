package com.cenk.valocase.adreward.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.adreward.domain.AdRewardClaim;
import com.cenk.valocase.adreward.domain.AdRewardType;
import com.cenk.valocase.adreward.dto.AdRewardClaimRequest;
import com.cenk.valocase.adreward.dto.AdRewardClaimResponse;
import com.cenk.valocase.adreward.dto.AdRewardPlacementStatus;
import com.cenk.valocase.adreward.dto.AdRewardStatusResponse;
import com.cenk.valocase.adreward.repository.AdRewardClaimRepository;
import com.cenk.valocase.catalog.domain.Skin;
import com.cenk.valocase.catalog.repository.SkinRepository;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.earnvp.domain.EarnVpSession;
import com.cenk.valocase.earnvp.repository.EarnVpSessionRepository;
import com.cenk.valocase.inventory.domain.InventoryItem;
import com.cenk.valocase.inventory.repository.InventoryItemRepository;
import com.cenk.valocase.upgrade.service.UpgradeContextKey;
import com.cenk.valocase.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;

/**
 * Server-authoritative rewarded-ad rewards with no daily limits. EARN_VP_2X arms a
 * timed 2x bonus on the player's current Earn VP session; the client never sets VP
 * amounts. UPGRADE_PLUS_5 issues a single
 * +5 buff bound to a server-derived upgrade context, consumed by the matching
 * upgrade attempt; the same context can never be buffed twice and buffs do not stack.
 *
 * Ad-network verification is not integrated yet: {@code adToken} is accepted as-is
 * and is the seam where server-side ad validation will be added.
 */
@Service
@RequiredArgsConstructor
public class AdRewardService {

    public static final String CODE_EARN_VP_2X_ACTIVE = "EARN_VP_2X_ALREADY_ACTIVE";
    public static final String CODE_UPGRADE_CONTEXT_USED = "UPGRADE_PLUS_5_ALREADY_USED_FOR_CONTEXT";
    public static final String CODE_NO_EARN_SESSION = "EARN_VP_NO_ACTIVE_SESSION";
    public static final String REASON_MARKET_VP = "AD_REWARD_MARKET_VP";
    public static final String CODE_MARKET_COOLDOWN = "MARKET_VP_COOLDOWN_ACTIVE";

    static final int MAX_AD_TOKEN_LENGTH = 100;
    static final long UNLIMITED_REMAINING_TODAY = 999_999L;
    static final Duration EARN_VP_2X_WINDOW = Duration.ofMinutes(3);
    static final long MARKET_CLAIMS_PER_CYCLE = 4L;
    static final Duration MARKET_COOLDOWN = Duration.ofMinutes(15);

    private final AdRewardClaimRepository adRewardClaimRepository;
    private final EarnVpSessionRepository earnVpSessionRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final SkinRepository skinRepository;
    private final AdRewardPolicy policy;
    private final WalletService walletService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public AdRewardStatusResponse getStatus(UUID accountId, String earnSessionId,
                                            List<String> inputItemIds, List<String> targetSkinIds) {
        return new AdRewardStatusResponse(List.of(
                earnVp2xStatus(accountId, earnSessionId),
                upgradePlus5Status(accountId, inputItemIds, targetSkinIds),
                marketVpStatus(accountId)));
    }

    @Transactional
    public AdRewardClaimResponse claim(UUID accountId, AdRewardType type, AdRewardClaimRequest request) {
        String adToken = normalizeAdToken(request.adToken());

        Optional<AdRewardClaim> existing =
                adRewardClaimRepository.findByAccountIdAndRewardTypeAndAdToken(accountId, type, adToken);
        if (existing.isPresent()) {
            return duplicateResponse(accountId, type, request);
        }

        return switch (type) {
            case EARN_VP_2X -> activateEarnVp2x(accountId, adToken, request.earnSessionId());
            case UPGRADE_PLUS_5 -> claimUpgradeBuff(accountId, adToken, request);
            case MARKET_VP_2500 -> grantMarketVp(accountId, adToken);
        };
    }

    @Transactional
    public AdRewardClaimResponse clearEarnVp2x(UUID accountId, String earnSessionId) {
        EarnVpSession session = requireEarnSessionForUpdate(accountId, earnSessionId);
        if (session.isBonus2xActive() || session.getBonus2xExpiresAt() != null) {
            session.setBonus2xActive(false);
            session.setBonus2xExpiresAt(null);
            earnVpSessionRepository.save(session);
        }
        return new AdRewardClaimResponse(
                AdRewardType.EARN_VP_2X.name(), "CLEARED", false, false, true, null, 0L, 0L, 0L, 0L, false, 0L);
    }

    /** Buff the matching upgrade context would add, without consuming it. Used by preview. */
    @Transactional(readOnly = true)
    public double peekUpgradeBuffPercentForContext(UUID accountId, String contextKey) {
        return adRewardClaimRepository
                .findByAccountIdAndRewardTypeAndSourceRefAndConsumedFalse(
                        accountId, AdRewardType.UPGRADE_PLUS_5, contextKey)
                .map(c -> c.getBuffPercent() != null ? c.getBuffPercent() : 0.0)
                .orElse(0.0);
    }

    /** Consumes the buff bound to a context and returns its size; 0 when none. Joins the caller's transaction. */
    @Transactional
    public double consumeUpgradeBuffForContext(UUID accountId, String contextKey) {
        List<AdRewardClaim> active =
                adRewardClaimRepository.findActiveUpgradeBuffForContextForUpdate(accountId, contextKey);
        if (active.isEmpty()) {
            return 0.0;
        }
        AdRewardClaim buff = active.get(0);
        buff.setConsumed(true);
        buff.setConsumedAt(Instant.now(clock));
        adRewardClaimRepository.save(buff);
        return buff.getBuffPercent() != null ? buff.getBuffPercent() : 0.0;
    }

    private AdRewardClaimResponse activateEarnVp2x(UUID accountId, String adToken, String earnSessionId) {
        UUID sessionId = requireEarnSessionId(earnSessionId);
        Instant now = Instant.now(clock);
        List<EarnVpSession> sessions = earnVpSessionRepository.findByAccountIdForUpdate(accountId);
        EarnVpSession session = sessions.stream()
                .filter(s -> sessionId.equals(s.getId()))
                .findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Start an Earn VP session before claiming the 2x bonus", CODE_NO_EARN_SESSION));

        Optional<EarnVpSession> activeSession = sessions.stream()
                .filter(s -> isBonus2xActive(s, now))
                .findFirst();
        if (activeSession.isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "A 2x Earn VP bonus is already active", CODE_EARN_VP_2X_ACTIVE);
        }

        session.setBonus2xActive(true);
        session.setBonus2xExpiresAt(now.plus(EARN_VP_2X_WINDOW));
        earnVpSessionRepository.save(session);

        AdRewardClaim claim = new AdRewardClaim();
        claim.setAccountId(accountId);
        claim.setRewardType(AdRewardType.EARN_VP_2X);
        claim.setAdToken(adToken);
        claim.setConsumed(true);
        claim.setSourceRef(session.getId().toString());
        claim.setCreatedAt(now);
        claim.setConsumedAt(now);
        try {
            adRewardClaimRepository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException e) {
            return duplicateEarnVp2x(session);
        }

        return new AdRewardClaimResponse(AdRewardType.EARN_VP_2X.name(), "OK",
                true, false, false, CODE_EARN_VP_2X_ACTIVE, remainingSeconds(session, now), 0L, 0L, 0L, false, 0L);
    }

    private AdRewardClaimResponse claimUpgradeBuff(UUID accountId, String adToken, AdRewardClaimRequest request) {
        String contextKey = resolveUpgradeContextKey(accountId, request.inputItemIds(), mergeTargetSkinIds(request));
        if (adRewardClaimRepository.existsByAccountIdAndRewardTypeAndSourceRef(
                accountId, AdRewardType.UPGRADE_PLUS_5, contextKey)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "This upgrade selection was already buffed", CODE_UPGRADE_CONTEXT_USED);
        }

        AdRewardClaim claim = new AdRewardClaim();
        claim.setAccountId(accountId);
        claim.setRewardType(AdRewardType.UPGRADE_PLUS_5);
        claim.setAdToken(adToken);
        claim.setBuffPercent(policy.upgradeBuffPercent());
        claim.setConsumed(false);
        claim.setSourceRef(contextKey);
        claim.setCreatedAt(Instant.now(clock));
        try {
            adRewardClaimRepository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException e) {
            return new AdRewardClaimResponse(AdRewardType.UPGRADE_PLUS_5.name(), "DUPLICATE",
                    false, true, false, CODE_UPGRADE_CONTEXT_USED, 0L, 0L, 0L, 0L, false, 0L);
        }

        return new AdRewardClaimResponse(AdRewardType.UPGRADE_PLUS_5.name(), "OK",
                false, true, false, CODE_UPGRADE_CONTEXT_USED, 0L, 0L, 0L, 0L, false, 0L);
    }

    private AdRewardClaimResponse grantMarketVp(UUID accountId, String adToken) {
        Instant now = Instant.now(clock);
        long priorCount = adRewardClaimRepository
                .countByAccountIdAndRewardType(accountId, AdRewardType.MARKET_VP_2500);

        long cooldownSecs = marketCooldownRemainingSeconds(accountId, priorCount, now);
        if (cooldownSecs > 0L) {
            long balance = walletService.getWalletForAccount(accountId).vpBalance();
            return new AdRewardClaimResponse(AdRewardType.MARKET_VP_2500.name(), "COOLDOWN",
                    false, false, false, CODE_MARKET_COOLDOWN, 0L, 0L, balance, 0L, true, cooldownSecs);
        }

        long reward = policy.marketVpReward();
        AdRewardClaim claim = new AdRewardClaim();
        claim.setAccountId(accountId);
        claim.setRewardType(AdRewardType.MARKET_VP_2500);
        claim.setAdToken(adToken);
        claim.setGrantedVp(reward);
        claim.setConsumed(true);
        claim.setCreatedAt(now);
        claim.setConsumedAt(now);
        try {
            adRewardClaimRepository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException e) {
            return marketDuplicate(accountId, adToken);
        }

        long newBalance = walletService.credit(accountId, reward, REASON_MARKET_VP, claim.getId()).getVpBalance();

        long newCount = priorCount + 1L;
        boolean cooldownNow = newCount % MARKET_CLAIMS_PER_CYCLE == 0L;
        long remaining = cooldownNow ? 0L : MARKET_CLAIMS_PER_CYCLE - (newCount % MARKET_CLAIMS_PER_CYCLE);
        long remainingCooldownSecs = cooldownNow ? MARKET_COOLDOWN.toSeconds() : 0L;
        return new AdRewardClaimResponse(AdRewardType.MARKET_VP_2500.name(), "OK",
                false, false, !cooldownNow, cooldownNow ? CODE_MARKET_COOLDOWN : null, 0L,
                reward, newBalance, remaining, cooldownNow, remainingCooldownSecs);
    }

    private AdRewardClaimResponse marketDuplicate(UUID accountId, String adToken) {
        long granted = adRewardClaimRepository
                .findByAccountIdAndRewardTypeAndAdToken(accountId, AdRewardType.MARKET_VP_2500, adToken)
                .map(c -> c.getGrantedVp() != null ? c.getGrantedVp() : 0L)
                .orElse(0L);
        long balance = walletService.getWalletForAccount(accountId).vpBalance();
        Instant now = Instant.now(clock);
        long count = adRewardClaimRepository.countByAccountIdAndRewardType(accountId, AdRewardType.MARKET_VP_2500);
        long cooldownSecs = marketCooldownRemainingSeconds(accountId, count, now);
        boolean cooldown = cooldownSecs > 0L;
        long remaining = cooldown ? 0L : MARKET_CLAIMS_PER_CYCLE - (count % MARKET_CLAIMS_PER_CYCLE);
        return new AdRewardClaimResponse(AdRewardType.MARKET_VP_2500.name(), "DUPLICATE",
                false, false, !cooldown, cooldown ? CODE_MARKET_COOLDOWN : null, 0L,
                granted, balance, remaining, cooldown, cooldownSecs);
    }

    // Cooldown applies only at a full-cycle boundary (count is a multiple of the cycle size).
    private long marketCooldownRemainingSeconds(UUID accountId, long count, Instant now) {
        if (count == 0L || count % MARKET_CLAIMS_PER_CYCLE != 0L) {
            return 0L;
        }
        Instant lastClaimAt = adRewardClaimRepository
                .findFirstByAccountIdAndRewardTypeOrderByCreatedAtDesc(accountId, AdRewardType.MARKET_VP_2500)
                .map(AdRewardClaim::getCreatedAt)
                .orElse(null);
        if (lastClaimAt == null) {
            return 0L;
        }
        Instant expiresAt = lastClaimAt.plus(MARKET_COOLDOWN);
        if (!now.isBefore(expiresAt)) {
            return 0L;
        }
        return (Duration.between(now, expiresAt).toMillis() + 999L) / 1_000L;
    }

    private AdRewardClaimResponse duplicateResponse(UUID accountId, AdRewardType type, AdRewardClaimRequest request) {
        if (type == AdRewardType.MARKET_VP_2500) {
            return marketDuplicate(accountId, normalizeAdToken(request.adToken()));
        }
        if (type == AdRewardType.EARN_VP_2X) {
            EarnVpSession session = requireEarnSession(accountId, request.earnSessionId());
            Instant now = Instant.now(clock);
            boolean active = isBonus2xActive(session, now);
            return new AdRewardClaimResponse(type.name(), "DUPLICATE", active, false,
                    !active, active ? CODE_EARN_VP_2X_ACTIVE : null, remainingSeconds(session, now),
                    0L, 0L, 0L, false, 0L);
        }
        String contextKey = tryComputeUpgradeContextKey(request.inputItemIds(), mergeTargetSkinIdsLenient(request));
        boolean used = contextKey != null && adRewardClaimRepository
                .existsByAccountIdAndRewardTypeAndSourceRef(accountId, AdRewardType.UPGRADE_PLUS_5, contextKey);
        boolean active = contextKey != null && adRewardClaimRepository
                .findByAccountIdAndRewardTypeAndSourceRefAndConsumedFalse(
                        accountId, AdRewardType.UPGRADE_PLUS_5, contextKey).isPresent();
        return new AdRewardClaimResponse(type.name(), "DUPLICATE", false, active,
                !used, used ? CODE_UPGRADE_CONTEXT_USED : null, 0L, 0L, 0L, 0L, false, 0L);
    }

    private AdRewardClaimResponse duplicateEarnVp2x(EarnVpSession session) {
        Instant now = Instant.now(clock);
        boolean active = isBonus2xActive(session, now);
        return new AdRewardClaimResponse(AdRewardType.EARN_VP_2X.name(), "DUPLICATE", active, false,
                !active, active ? CODE_EARN_VP_2X_ACTIVE : null, remainingSeconds(session, now),
                0L, 0L, 0L, false, 0L);
    }

    private AdRewardPlacementStatus earnVp2xStatus(UUID accountId, String earnSessionId) {
        if (earnSessionId == null || earnSessionId.isBlank()) {
            return new AdRewardPlacementStatus(AdRewardType.EARN_VP_2X.name(), false,
                    UNLIMITED_REMAINING_TODAY, CODE_NO_EARN_SESSION, false, false, false, 0L, 0L);
        }
        EarnVpSession session = requireEarnSession(accountId, earnSessionId);
        Instant now = Instant.now(clock);
        EarnVpSession statusSession = earnVpSessionRepository
                .findFirstByAccountIdAndBonus2xExpiresAtAfterOrderByBonus2xExpiresAtDesc(accountId, now)
                .orElse(session);
        boolean active = isBonus2xActive(statusSession, now);
        return new AdRewardPlacementStatus(AdRewardType.EARN_VP_2X.name(), !active,
                UNLIMITED_REMAINING_TODAY, active ? CODE_EARN_VP_2X_ACTIVE : null, active, false, false, 0L,
                remainingSeconds(statusSession, now));
    }

    private AdRewardPlacementStatus upgradePlus5Status(UUID accountId, List<String> inputItemIds,
                                                       List<String> targetSkinIds) {
        String contextKey = tryComputeUpgradeContextKey(inputItemIds, targetSkinIds);
        if (contextKey == null) {
            boolean anyActive = adRewardClaimRepository
                    .existsByAccountIdAndRewardTypeAndConsumedFalse(accountId, AdRewardType.UPGRADE_PLUS_5);
            return new AdRewardPlacementStatus(AdRewardType.UPGRADE_PLUS_5.name(), true,
                    UNLIMITED_REMAINING_TODAY, null,
                    false, anyActive, false, 0L, 0L);
        }
        boolean active = adRewardClaimRepository
                .findByAccountIdAndRewardTypeAndSourceRefAndConsumedFalse(
                        accountId, AdRewardType.UPGRADE_PLUS_5, contextKey).isPresent();
        boolean used = adRewardClaimRepository
                .existsByAccountIdAndRewardTypeAndSourceRef(accountId, AdRewardType.UPGRADE_PLUS_5, contextKey);
        return new AdRewardPlacementStatus(AdRewardType.UPGRADE_PLUS_5.name(), !used,
                UNLIMITED_REMAINING_TODAY, used ? CODE_UPGRADE_CONTEXT_USED : null,
                false, active, used, 0L, 0L);
    }

    private AdRewardPlacementStatus marketVpStatus(UUID accountId) {
        Instant now = Instant.now(clock);
        long count = adRewardClaimRepository.countByAccountIdAndRewardType(accountId, AdRewardType.MARKET_VP_2500);
        long cooldownSecs = marketCooldownRemainingSeconds(accountId, count, now);
        boolean cooldown = cooldownSecs > 0L;
        long remaining = cooldown ? 0L : MARKET_CLAIMS_PER_CYCLE - (count % MARKET_CLAIMS_PER_CYCLE);
        return new AdRewardPlacementStatus(AdRewardType.MARKET_VP_2500.name(), !cooldown,
                remaining, cooldown ? CODE_MARKET_COOLDOWN : null, false, false, false, cooldownSecs, 0L);
    }

    private EarnVpSession requireEarnSessionForUpdate(UUID accountId, String rawSessionId) {
        UUID id = requireEarnSessionId(rawSessionId);
        return earnVpSessionRepository.findByIdAndAccountIdForUpdate(id, accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Start an Earn VP session before claiming the 2x bonus", CODE_NO_EARN_SESSION));
    }

    private EarnVpSession requireEarnSession(UUID accountId, String rawSessionId) {
        UUID id = requireEarnSessionId(rawSessionId);
        return earnVpSessionRepository.findByIdAndAccountId(id, accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "Start an Earn VP session before claiming the 2x bonus", CODE_NO_EARN_SESSION));
    }

    private UUID requireEarnSessionId(String rawSessionId) {
        if (rawSessionId == null || rawSessionId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "earnSessionId is required", CODE_NO_EARN_SESSION);
        }
        return parseUuid(rawSessionId, "earnSessionId");
    }

    private boolean isBonus2xActive(EarnVpSession session, Instant now) {
        return session.getBonus2xExpiresAt() != null && now.isBefore(session.getBonus2xExpiresAt());
    }

    private long remainingSeconds(EarnVpSession session, Instant now) {
        Instant expiresAt = session.getBonus2xExpiresAt();
        if (expiresAt == null || !now.isBefore(expiresAt)) {
            return 0L;
        }
        long millis = Duration.between(now, expiresAt).toMillis();
        return (millis + 999L) / 1_000L;
    }

    private String resolveUpgradeContextKey(UUID accountId, List<String> rawInputItemIds, List<String> targetSkinIds) {
        Set<UUID> itemIds = parseInputItemIds(rawInputItemIds);
        List<String> targets = validateTargets(targetSkinIds);

        List<InventoryItem> owned = inventoryItemRepository.findByIdInAndAccountId(itemIds, accountId);
        if (owned.size() != itemIds.size()) {
            throw new ApiException(HttpStatus.NOT_FOUND,
                    "One or more input items are not owned or no longer available");
        }
        List<Skin> skins = skinRepository.findAllById(targets);
        if (skins.size() != targets.size() || skins.stream().anyMatch(s -> !s.isActive())) {
            throw new ApiException(HttpStatus.NOT_FOUND, "One or more target skins are not available");
        }
        return UpgradeContextKey.compute(itemIds, targets);
    }

    private String tryComputeUpgradeContextKey(List<String> rawInputItemIds, List<String> targetSkinIds) {
        if (rawInputItemIds == null || rawInputItemIds.isEmpty()
                || targetSkinIds == null || targetSkinIds.isEmpty()) {
            return null;
        }
        try {
            Set<UUID> itemIds = parseInputItemIds(rawInputItemIds);
            List<String> targets = validateTargets(targetSkinIds);
            return UpgradeContextKey.compute(itemIds, targets);
        } catch (ApiException e) {
            return null;
        }
    }

    private Set<UUID> parseInputItemIds(List<String> rawInputItemIds) {
        if (rawInputItemIds == null || rawInputItemIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "inputItemIds must not be empty");
        }
        Set<UUID> ids = new LinkedHashSet<>();
        for (String raw : rawInputItemIds) {
            if (raw == null || raw.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "inputItemIds contains a blank id");
            }
            ids.add(parseUuid(raw, "inputItemId"));
        }
        return ids;
    }

    private List<String> validateTargets(List<String> targetSkinIds) {
        if (targetSkinIds == null || targetSkinIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "targetSkinIds must not be empty");
        }
        Set<String> distinct = new LinkedHashSet<>();
        for (String raw : targetSkinIds) {
            if (raw == null || raw.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "targetSkinIds contains a blank id");
            }
            distinct.add(raw.trim());
        }
        return new ArrayList<>(distinct);
    }

    private List<String> mergeTargetSkinIds(AdRewardClaimRequest request) {
        if (request.targetSkinIds() != null && !request.targetSkinIds().isEmpty()) {
            return request.targetSkinIds();
        }
        if (request.targetSkinId() != null && !request.targetSkinId().isBlank()) {
            return List.of(request.targetSkinId());
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "targetSkinIds must not be empty");
    }

    private List<String> mergeTargetSkinIdsLenient(AdRewardClaimRequest request) {
        if (request.targetSkinIds() != null && !request.targetSkinIds().isEmpty()) {
            return request.targetSkinIds();
        }
        if (request.targetSkinId() != null && !request.targetSkinId().isBlank()) {
            return List.of(request.targetSkinId());
        }
        return List.of();
    }

    private UUID parseUuid(String raw, String field) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Invalid " + field + ": " + raw);
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
