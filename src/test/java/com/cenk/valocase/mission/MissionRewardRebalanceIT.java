package com.cenk.valocase.mission;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.domain.AccountStatus;
import com.cenk.valocase.account.repository.AccountRepository;
import com.cenk.valocase.mission.dto.MissionClaimResponse;
import com.cenk.valocase.mission.dto.MissionResponse;
import com.cenk.valocase.mission.event.MissionEventTypes;
import com.cenk.valocase.mission.service.MissionProgressService;
import com.cenk.valocase.mission.service.MissionService;
import com.cenk.valocase.wallet.service.WalletService;

/**
 * Verifies the V70 reward rebalance against a real Postgres seeded by Flyway:
 * the Flyway-seeded mission_definitions are the source of truth, so the mission
 * list returns the new reward_vp values and claiming credits the new amount.
 */
@SpringBootTest
@Testcontainers
class MissionRewardRebalanceIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired JdbcTemplate jdbc;
    @Autowired AccountRepository accountRepository;
    @Autowired WalletService walletService;
    @Autowired MissionService missionService;
    @Autowired MissionProgressService missionProgressService;

    /** code -> rebalanced reward (the new economy values). */
    private static final Map<String, Long> EXPECTED_REWARDS = Map.of(
            "OPEN_3_CASES", 500L,
            "SELL_2_SKINS", 500L,
            "UPGRADE_1_SUCCESS", 500L,
            "EARN_3000_VP", 500L,
            "SELL_3000_VP", 500L,
            "WIN_2_UPGRADES", 400L,
            "PLAY_2_BATTLES", 500L,
            "OPEN_10_CASES", 1500L,
            "WIN_1_BATTLE", 500L);

    @Test
    void missionListReturnsRebalancedRewardForEveryMission() {
        UUID accountId = createAccount();

        Map<String, Long> rewardByCode = missionService.getMissions(accountId).stream()
                .collect(java.util.stream.Collectors.toMap(MissionResponse::code, MissionResponse::rewardVp));

        EXPECTED_REWARDS.forEach((code, expected) ->
                assertEquals(expected, rewardByCode.get(code), "reward_vp for mission " + code));
    }

    @Test
    void claimCreditsRebalancedReward() {
        UUID accountId = createAccount();
        walletService.createInitialWallet(accountId, 10_000L);

        // Complete "Sell 2 Skins" (300 -> 500 VP after rebalance): 2 SKIN_SOLD events.
        missionProgressService.recordProgress(accountId, MissionEventTypes.SKIN_SOLD, 2);

        UUID sellTwoSkinsId = jdbc.queryForObject(
                "SELECT id FROM mission_definitions WHERE code = 'SELL_2_SKINS'", UUID.class);

        MissionClaimResponse claim = missionService.claim(accountId, sellTwoSkinsId);

        assertEquals(500L, claim.rewardVp());
        assertEquals(10_500L, claim.newVpBalance());
    }

    private UUID createAccount() {
        Account account = new Account();
        account.setGuestToken(UUID.randomUUID());
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setLastSeenAt(Instant.now());
        return accountRepository.save(account).getId();
    }
}
