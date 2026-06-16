package com.cenk.valocase.earnvp.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.earnvp.domain.EarnVpClaim;
import com.cenk.valocase.earnvp.dto.EarnVpClaimResponse;
import com.cenk.valocase.earnvp.repository.EarnVpClaimRepository;
import com.cenk.valocase.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;

/**
 * Server-authoritative Earn VP. The client reports taps and duration only; the
 * backend caps the tap rate, computes the reward, and grants it through the
 * wallet. A claim and its credit commit together; the unique
 * (account_id, client_session_id) constraint prevents a double grant.
 */
@Service
@RequiredArgsConstructor
public class EarnVpService {

    public static final String REASON_EARN_VP = "EARN_VP";

    static final BigDecimal BASE_REWARD = new BigDecimal("1.6");
    static final BigDecimal MULTIPLIER_START = BigDecimal.ONE;
    static final BigDecimal MULTIPLIER_STEP = new BigDecimal("0.02");
    static final BigDecimal MULTIPLIER_MAX = new BigDecimal("3.0");

    private static final BigDecimal MILLIS_PER_SECOND = new BigDecimal("1000");
    private static final BigDecimal DECAY_SLOW = new BigDecimal("0.05");
    private static final BigDecimal DECAY_FAST = new BigDecimal("0.325");
    private static final BigDecimal DECAY_VERY_FAST = new BigDecimal("0.78");
    private static final BigDecimal IDLE_FAST_AFTER_SEC = new BigDecimal("1.0");
    private static final BigDecimal IDLE_VERY_FAST_AFTER_SEC = new BigDecimal("1.75");

    static final int MAX_TAP_RATE_PER_SECOND = 10;
    static final long MIN_DURATION_MS = 1_000L;
    static final long MAX_DURATION_MS = 240_000L;
    static final int MAX_TAPS_PER_CLAIM = MAX_TAP_RATE_PER_SECOND * (int) (MAX_DURATION_MS / 1_000L);
    static final long MIN_CLAIM_INTERVAL_MS = 1_000L;
    static final int MAX_SESSION_ID_LENGTH = 100;

    private final EarnVpClaimRepository earnVpClaimRepository;
    private final WalletService walletService;

    @Transactional
    public EarnVpClaimResponse claim(UUID accountId, Integer tapCount, Long sessionDurationMs,
                                     String clientSessionId, List<Long> tapOffsetsMs) {
        String sessionId = normalizeSessionId(clientSessionId);
        validate(tapCount, sessionDurationMs);

        Optional<EarnVpClaim> existing = earnVpClaimRepository.findByAccountIdAndClientSessionId(accountId, sessionId);
        if (existing.isPresent()) {
            return duplicateResponse(accountId, existing.get());
        }

        enforceRateLimit(accountId);

        int cappedTaps = acceptedTaps(tapCount, sessionDurationMs);
        List<Long> offsets = sanitizeOffsets(tapOffsetsMs, sessionDurationMs);

        int acceptedTapCount;
        long vpGranted;
        if (offsets.isEmpty()) {
            acceptedTapCount = cappedTaps;
            vpGranted = computeReward(acceptedTapCount);
        } else {
            acceptedTapCount = Math.min(cappedTaps, offsets.size());
            vpGranted = computeTimedReward(offsets.subList(0, acceptedTapCount));
        }

        EarnVpClaim claim = new EarnVpClaim();
        claim.setAccountId(accountId);
        claim.setClientSessionId(sessionId);
        claim.setTapCountAccepted(acceptedTapCount);
        claim.setVpGranted(vpGranted);
        claim.setCreatedAt(Instant.now());
        try {
            earnVpClaimRepository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException e) {
            EarnVpClaim raced = earnVpClaimRepository.findByAccountIdAndClientSessionId(accountId, sessionId)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Duplicate earn session"));
            return duplicateResponse(accountId, raced);
        }

        long newBalance = walletService.credit(accountId, vpGranted, REASON_EARN_VP, claim.getId()).getVpBalance();
        return new EarnVpClaimResponse(vpGranted, newBalance, acceptedTapCount, "OK");
    }

    // Aggregate fallback: continuous taps, no idle-decay (no per-tap timing available).
    private long computeReward(int acceptedTapCount) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal multiplier = MULTIPLIER_START;
        for (int tap = 0; tap < acceptedTapCount; tap++) {
            total = total.add(BASE_REWARD.multiply(multiplier));
            multiplier = multiplier.add(MULTIPLIER_STEP).min(MULTIPLIER_MAX);
        }
        return total.setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    private long computeTimedReward(List<Long> sortedOffsetsMs) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal multiplier = MULTIPLIER_START;
        Long previousOffset = null;
        for (Long offset : sortedOffsetsMs) {
            if (previousOffset != null) {
                multiplier = applyDecay(multiplier, offset - previousOffset);
            }
            total = total.add(BASE_REWARD.multiply(multiplier));
            multiplier = multiplier.add(MULTIPLIER_STEP).min(MULTIPLIER_MAX);
            previousOffset = offset;
        }
        return total.setScale(0, RoundingMode.FLOOR).longValueExact();
    }

    private BigDecimal applyDecay(BigDecimal multiplier, long idleMs) {
        if (multiplier.compareTo(MULTIPLIER_START) <= 0) {
            return MULTIPLIER_START;
        }
        BigDecimal idleSeconds = BigDecimal.valueOf(idleMs).divide(MILLIS_PER_SECOND);
        BigDecimal speed = idleSeconds.compareTo(IDLE_FAST_AFTER_SEC) < 0 ? DECAY_SLOW
                : idleSeconds.compareTo(IDLE_VERY_FAST_AFTER_SEC) < 0 ? DECAY_FAST
                : DECAY_VERY_FAST;
        return multiplier.subtract(speed.multiply(idleSeconds)).max(MULTIPLIER_START);
    }

    private List<Long> sanitizeOffsets(List<Long> raw, long sessionDurationMs) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<Long> cleaned = new ArrayList<>(raw.size());
        for (Long offset : raw) {
            if (offset == null || offset < 0L) {
                continue;
            }
            cleaned.add(Math.min(offset, sessionDurationMs));
        }
        Collections.sort(cleaned);
        return cleaned;
    }

    private int acceptedTaps(int tapCount, long sessionDurationMs) {
        long clampedDurationMs = Math.min(Math.max(sessionDurationMs, MIN_DURATION_MS), MAX_DURATION_MS);
        int perDurationCap = (int) (MAX_TAP_RATE_PER_SECOND * clampedDurationMs / 1_000L);
        return Math.min(tapCount, Math.min(perDurationCap, MAX_TAPS_PER_CLAIM));
    }

    private void enforceRateLimit(UUID accountId) {
        earnVpClaimRepository.findTopByAccountIdOrderByCreatedAtDesc(accountId).ifPresent(last -> {
            if (Duration.between(last.getCreatedAt(), Instant.now()).toMillis() < MIN_CLAIM_INTERVAL_MS) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Earn VP claims are too frequent; wait a moment");
            }
        });
    }

    private EarnVpClaimResponse duplicateResponse(UUID accountId, EarnVpClaim claim) {
        long balance = walletService.getWalletForAccount(accountId).vpBalance();
        return new EarnVpClaimResponse(claim.getVpGranted(), balance, claim.getTapCountAccepted(), "DUPLICATE");
    }

    private String normalizeSessionId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientSessionId is required");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_SESSION_ID_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "clientSessionId is too long");
        }
        return trimmed;
    }

    private void validate(Integer tapCount, Long sessionDurationMs) {
        if (tapCount == null || tapCount <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "tapCount must be positive");
        }
        if (sessionDurationMs == null || sessionDurationMs <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "sessionDurationMs must be positive");
        }
    }
}
