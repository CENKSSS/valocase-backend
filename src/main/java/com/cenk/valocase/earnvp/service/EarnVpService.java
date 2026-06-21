package com.cenk.valocase.earnvp.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.earnvp.domain.EarnVpClaim;
import com.cenk.valocase.earnvp.domain.EarnVpSession;
import com.cenk.valocase.earnvp.dto.EarnVpClaimResponse;
import com.cenk.valocase.earnvp.dto.EarnVpSessionResponse;
import com.cenk.valocase.earnvp.repository.EarnVpClaimRepository;
import com.cenk.valocase.earnvp.repository.EarnVpSessionRepository;
import com.cenk.valocase.mission.event.MissionEventTypes;
import com.cenk.valocase.mission.event.MissionProgressEvent;
import com.cenk.valocase.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;

/**
 * Server-authoritative Earn VP. A session is started server-side first; the claim
 * derives elapsed duration from the recorded start time (client-reported duration
 * is ignored), caps the tap rate, computes the reward, and grants it through the
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
    static final long MAX_DURATION_MS = 240_000L;
    static final int MAX_TAPS_PER_CLAIM = MAX_TAP_RATE_PER_SECOND * (int) (MAX_DURATION_MS / 1_000L);
    static final long MIN_CLAIM_INTERVAL_MS = 1_000L;
    static final int MAX_SESSION_ID_LENGTH = 100;

    private final EarnVpClaimRepository earnVpClaimRepository;
    private final EarnVpSessionRepository earnVpSessionRepository;
    private final WalletService walletService;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public EarnVpSessionResponse startSession(UUID accountId) {
        Instant now = Instant.now(clock);
        EarnVpSession session = new EarnVpSession();
        session.setAccountId(accountId);
        session.setStartedAt(now);
        session.setCreatedAt(now);
        session = earnVpSessionRepository.saveAndFlush(session);
        return new EarnVpSessionResponse(
                session.getId().toString(), now.toEpochMilli(),
                MAX_DURATION_MS, MAX_TAP_RATE_PER_SECOND, MAX_TAPS_PER_CLAIM);
    }

    @Transactional
    public EarnVpClaimResponse claim(UUID accountId, Integer tapCount,
                                     String clientSessionId, List<Long> tapOffsetsMs) {
        String sessionId = normalizeSessionId(clientSessionId);
        validate(tapCount);

        Optional<EarnVpClaim> existing = earnVpClaimRepository.findByAccountIdAndClientSessionId(accountId, sessionId);
        if (existing.isPresent()) {
            return duplicateResponse(accountId, existing.get());
        }

        long serverDurationMs = elapsedMs(requireSession(accountId, sessionId));
        enforceRateLimit(accountId);

        int cappedTaps = acceptedTaps(tapCount, serverDurationMs);
        List<Long> offsets = sanitizeOffsets(tapOffsetsMs, serverDurationMs);

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
        claim.setCreatedAt(Instant.now(clock));
        try {
            earnVpClaimRepository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException e) {
            EarnVpClaim raced = earnVpClaimRepository.findByAccountIdAndClientSessionId(accountId, sessionId)
                    .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "Duplicate earn session"));
            return duplicateResponse(accountId, raced);
        }

        long newBalance = walletService.credit(accountId, vpGranted, REASON_EARN_VP, claim.getId()).getVpBalance();
        eventPublisher.publishEvent(new MissionProgressEvent(
                accountId, MissionEventTypes.VP_EARNED, (int) Math.min(vpGranted, Integer.MAX_VALUE)));
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

    // Server elapsed is authoritative; client-reported duration is never used.
    private int acceptedTaps(int tapCount, long serverDurationMs) {
        long boundedMs = Math.min(serverDurationMs, MAX_DURATION_MS);
        int perDurationCap = (int) (MAX_TAP_RATE_PER_SECOND * boundedMs / 1_000L);
        return Math.min(tapCount, Math.min(perDurationCap, MAX_TAPS_PER_CLAIM));
    }

    private EarnVpSession requireSession(UUID accountId, String sessionId) {
        UUID id;
        try {
            id = UUID.fromString(sessionId);
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Earn VP session not started; start a session first");
        }
        return earnVpSessionRepository.findByIdAndAccountId(id, accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                        "Earn VP session not started; start a session first"));
    }

    private long elapsedMs(EarnVpSession session) {
        return Math.max(0L, Duration.between(session.getStartedAt(), Instant.now(clock)).toMillis());
    }

    private void enforceRateLimit(UUID accountId) {
        earnVpClaimRepository.findTopByAccountIdOrderByCreatedAtDesc(accountId).ifPresent(last -> {
            if (Duration.between(last.getCreatedAt(), Instant.now(clock)).toMillis() < MIN_CLAIM_INTERVAL_MS) {
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

    private void validate(Integer tapCount) {
        if (tapCount == null || tapCount <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "tapCount must be positive");
        }
    }
}
