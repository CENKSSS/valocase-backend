package com.cenk.valocase.daily.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.daily.domain.DailyClaim;
import com.cenk.valocase.daily.domain.DailyRewardState;
import com.cenk.valocase.daily.dto.DailyClaimResponse;
import com.cenk.valocase.daily.dto.DailyStatusResponse;
import com.cenk.valocase.daily.repository.DailyClaimRepository;
import com.cenk.valocase.daily.repository.DailyRewardStateRepository;
import com.cenk.valocase.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;

/**
 * Server-authoritative daily reward: a flat grant on a rolling 24h cooldown,
 * unconditional (no missions, ads, streak, level, or inventory). The cooldown is
 * enforced from the stored last-claim instant under a pessimistic row lock, so a
 * second claim within 24h is rejected regardless of what the client sends.
 */
@Service
@RequiredArgsConstructor
public class DailyRewardService {

    public static final String REASON_DAILY_REWARD = "DAILY_REWARD";

    static final long REWARD_VP = 2000L;
    static final Duration COOLDOWN = Duration.ofHours(24);

    private final DailyRewardStateRepository dailyRewardStateRepository;
    private final DailyClaimRepository dailyClaimRepository;
    private final WalletService walletService;

    @Transactional(readOnly = true)
    public DailyStatusResponse getStatus(UUID accountId) {
        Instant now = Instant.now();
        Optional<DailyRewardState> stateOpt = dailyRewardStateRepository.findById(accountId);

        if (stateOpt.isEmpty()) {
            return new DailyStatusResponse(true, REWARD_VP, null, null, 0);
        }

        Instant lastClaimAt = stateOpt.get().getLastClaimAt();
        Instant nextClaimAt = lastClaimAt.plus(COOLDOWN);
        boolean claimable = !now.isBefore(nextClaimAt);
        long secondsUntilNextClaim = claimable ? 0 : Math.max(0, ChronoUnit.SECONDS.between(now, nextClaimAt));

        return new DailyStatusResponse(claimable, REWARD_VP, lastClaimAt, nextClaimAt, secondsUntilNextClaim);
    }

    @Transactional
    public DailyClaimResponse claim(UUID accountId) {
        Instant now = Instant.now();
        Optional<DailyRewardState> stateOpt = dailyRewardStateRepository.findForUpdate(accountId);

        if (stateOpt.isPresent()) {
            Instant nextClaimAt = stateOpt.get().getLastClaimAt().plus(COOLDOWN);
            if (now.isBefore(nextClaimAt)) {
                long secondsRemaining = Math.max(0, ChronoUnit.SECONDS.between(now, nextClaimAt));
                throw new ApiException(HttpStatus.CONFLICT,
                        "Daily reward on cooldown; " + secondsRemaining + "s remaining", "DAILY_REWARD_ON_COOLDOWN");
            }
        }

        DailyClaim claim = new DailyClaim();
        claim.setAccountId(accountId);
        claim.setRewardVp(REWARD_VP);
        claim.setCreatedAt(now);
        dailyClaimRepository.save(claim);

        DailyRewardState state = stateOpt.orElseGet(() -> {
            DailyRewardState s = new DailyRewardState();
            s.setAccountId(accountId);
            return s;
        });
        state.setLastClaimAt(now);
        state.setUpdatedAt(now);
        dailyRewardStateRepository.save(state);

        long newVpBalance = walletService.credit(accountId, REWARD_VP, REASON_DAILY_REWARD, claim.getId()).getVpBalance();

        return new DailyClaimResponse(REWARD_VP, newVpBalance, now, now.plus(COOLDOWN));
    }
}
