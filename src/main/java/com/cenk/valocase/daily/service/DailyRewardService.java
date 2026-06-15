package com.cenk.valocase.daily.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
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
 * Server-authoritative daily rewards. "Day" is a UTC calendar day; rewards scale
 * with the consecutive-day streak. A claim and its wallet credit commit together;
 * the unique (account_id, claim_date) constraint plus a locked state row prevent
 * a double claim.
 */
@Service
@RequiredArgsConstructor
public class DailyRewardService {

    public static final String REASON_DAILY_REWARD = "DAILY_REWARD";

    private final DailyRewardStateRepository dailyRewardStateRepository;
    private final DailyClaimRepository dailyClaimRepository;
    private final WalletService walletService;

    @Transactional(readOnly = true)
    public DailyStatusResponse getStatus(UUID accountId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Optional<DailyRewardState> stateOpt = dailyRewardStateRepository.findById(accountId);

        if (stateOpt.isEmpty()) {
            // Never claimed: claimable now, streak 0, next reward is day 1.
            return new DailyStatusResponse(true, 0, rewardForStreak(1), null, today, 0);
        }

        DailyRewardState state = stateOpt.get();
        boolean claimedToday = state.getLastClaimDate().isEqual(today);
        boolean claimable = !claimedToday;

        int nextStreak;
        if (claimable) {
            nextStreak = state.getLastClaimDate().isEqual(today.minusDays(1)) ? state.getCurrentStreak() + 1 : 1;
        } else {
            // Already claimed today; the next claim (tomorrow) continues the streak.
            nextStreak = state.getCurrentStreak() + 1;
        }

        LocalDate nextClaimDate = claimable ? today : today.plusDays(1);
        long secondsUntilNextClaim = claimable
                ? 0
                : ChronoUnit.SECONDS.between(Instant.now(), today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());

        return new DailyStatusResponse(
                claimable,
                state.getCurrentStreak(),
                rewardForStreak(nextStreak),
                state.getLastClaimDate(),
                nextClaimDate,
                Math.max(0, secondsUntilNextClaim)
        );
    }

    @Transactional
    public DailyClaimResponse claim(UUID accountId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Optional<DailyRewardState> stateOpt = dailyRewardStateRepository.findForUpdate(accountId);

        int newStreak;
        if (stateOpt.isPresent()) {
            DailyRewardState state = stateOpt.get();
            if (state.getLastClaimDate().isEqual(today)) {
                throw new ApiException(HttpStatus.CONFLICT, "Daily reward already claimed today");
            }
            newStreak = state.getLastClaimDate().isEqual(today.minusDays(1)) ? state.getCurrentStreak() + 1 : 1;
        } else {
            newStreak = 1;
        }

        long rewardVp = rewardForStreak(newStreak);
        Instant now = Instant.now();

        DailyClaim claim = new DailyClaim();
        claim.setAccountId(accountId);
        claim.setClaimDate(today);
        claim.setStreak(newStreak);
        claim.setRewardVp(rewardVp);
        claim.setCreatedAt(now);
        try {
            dailyClaimRepository.saveAndFlush(claim);
        } catch (DataIntegrityViolationException e) {
            // Race: another claim for the same date committed first.
            throw new ApiException(HttpStatus.CONFLICT, "Daily reward already claimed today");
        }

        DailyRewardState state = stateOpt.orElseGet(() -> {
            DailyRewardState s = new DailyRewardState();
            s.setAccountId(accountId);
            return s;
        });
        state.setLastClaimDate(today);
        state.setCurrentStreak(newStreak);
        state.setUpdatedAt(now);
        dailyRewardStateRepository.save(state);

        long newVpBalance = walletService.credit(accountId, rewardVp, REASON_DAILY_REWARD, claim.getId()).getVpBalance();

        return new DailyClaimResponse(rewardVp, newStreak, newVpBalance, today);
    }

    /** Daily reward schedule by streak day (7+ caps at 750 VP). */
    static long rewardForStreak(int streak) {
        return switch (streak) {
            case 1 -> 100;
            case 2 -> 150;
            case 3 -> 200;
            case 4 -> 300;
            case 5 -> 400;
            case 6 -> 500;
            default -> 750;
        };
    }
}
