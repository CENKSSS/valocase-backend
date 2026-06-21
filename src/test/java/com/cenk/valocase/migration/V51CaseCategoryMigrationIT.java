package com.cenk.valocase.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
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
import com.cenk.valocase.caseopening.service.CaseOpeningService;
import com.cenk.valocase.progression.CategoryLockedException;
import com.cenk.valocase.progression.domain.CaseCategory;
import com.cenk.valocase.wallet.service.WalletService;

@SpringBootTest
@Testcontainers
class V51CaseCategoryMigrationIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    AccountRepository accountRepository;

    @Autowired
    WalletService walletService;

    @Autowired
    CaseOpeningService caseOpeningService;

    @Test
    void oldCaseIdsAreRetiredAndInactive() {
        assertFalse(active("protocol_melee"));
        assertFalse(active("radiant_melee"));
        assertFalse(active("protocol_bulldog"));
    }

    @Test
    void renamedCaseIdsExistAndAreActive() {
        assertTrue(active("melee_protocol"));
        assertTrue(active("melee_radiant"));
        assertTrue(active("bulldog_protocol"));
    }

    @Test
    void dropPoolsMovedToRenamedIds() {
        assertTrue(entryCount("melee_protocol") > 0);
        assertTrue(entryCount("melee_radiant") > 0);
        assertTrue(entryCount("bulldog_protocol") > 0);

        assertEquals(0, entryCount("protocol_melee"));
        assertEquals(0, entryCount("radiant_melee"));
        assertEquals(0, entryCount("protocol_bulldog"));
    }

    @Test
    void renamedIdsResolveToExpectedCategory() {
        assertEquals(Optional.of(CaseCategory.MELEE), CaseCategory.fromCaseId("melee_protocol"));
        assertEquals(Optional.of(CaseCategory.MELEE), CaseCategory.fromCaseId("melee_radiant"));
        assertEquals(Optional.of(CaseCategory.BULLDOG), CaseCategory.fromCaseId("bulldog_protocol"));
    }

    @Test
    void levelOneCannotOpenRenamedGatedCases() {
        UUID accountId = createLevelOneAccount();

        assertThrows(CategoryLockedException.class,
                () -> caseOpeningService.open(accountId, "melee_protocol"));
        assertThrows(CategoryLockedException.class,
                () -> caseOpeningService.open(accountId, "melee_radiant"));
        assertThrows(CategoryLockedException.class,
                () -> caseOpeningService.open(accountId, "bulldog_protocol"));
    }

    private UUID createLevelOneAccount() {
        Account account = new Account();
        account.setGuestToken(UUID.randomUUID());
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setLastSeenAt(Instant.now());
        account.setLevel(1);
        account = accountRepository.save(account);
        walletService.createInitialWallet(account.getId(), 100000L);
        return account.getId();
    }

    private boolean active(String caseId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT active FROM case_definitions WHERE id = ?", Boolean.class, caseId));
    }

    private int entryCount(String caseId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM case_entries WHERE case_id = ?", Integer.class, caseId);
        return count == null ? 0 : count;
    }
}
