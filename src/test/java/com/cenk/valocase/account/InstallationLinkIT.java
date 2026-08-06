package com.cenk.valocase.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cenk.valocase.account.dto.GuestRegisterResponse;
import com.cenk.valocase.account.service.AccountService;

/**
 * The installation -> account link against a real Flyway-migrated Postgres.
 *
 * <p>Two things can only be proven here and not in the unit tests. The column has
 * to exist with the type the entity declares, or {@code ddl-auto=validate} takes
 * the whole application down at startup rather than failing one request — that is
 * a deployment outage, so it is worth an integration test of its own. And the
 * value has to survive the round trip as a UUID that joins against
 * {@code player_sessions.installation_id} without a cast, which is the entire
 * reason the column is UUID rather than text.
 *
 * <p>No Testcontainers — this project has no Docker. Point the usual Spring
 * datasource properties at any Postgres and run it.
 */
@SpringBootTest
class InstallationLinkIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired AccountService accountService;

    private String uniqueName() {
        return "N" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private UUID storedInstallation(String accountId) {
        return jdbc.queryForObject(
                "SELECT installation_id FROM accounts WHERE id = ?::uuid",
                UUID.class, accountId);
    }

    @Test
    void theInstallationIdSurvivesTheRoundTripAsAUuid() {
        UUID installation = UUID.randomUUID();

        GuestRegisterResponse res =
                accountService.registerGuest(uniqueName(), "TR", installation.toString());

        assertEquals(installation, storedInstallation(res.accountId()));
    }

    @Test
    void anOlderClientLeavesTheColumnNullAndStillGetsAnAccountAndAWallet() {
        GuestRegisterResponse res = accountService.registerGuest(uniqueName(), "TR");

        assertNotNull(res.accountId(), "account created");
        assertNull(storedInstallation(res.accountId()), "no id invented");

        Integer wallets = jdbc.queryForObject(
                "SELECT COUNT(*) FROM wallets WHERE account_id = ?::uuid",
                Integer.class, res.accountId());
        assertEquals(1, wallets, "the wallet is created exactly as before");
    }

    @Test
    void aMalformedIdIsDroppedAndTheAccountIsStillWritten() {
        GuestRegisterResponse res =
                accountService.registerGuest(uniqueName(), "TR", "not-a-uuid");

        assertNotNull(res.accountId());
        assertNull(storedInstallation(res.accountId()));
    }

    @Test
    void oneInstallationMayHoldSeveralAccountsBecauseThereIsNoUniqueConstraint() {
        // The production fact this defends: installation 5f291722-... already owns three
        // accounts. A UNIQUE index here would have refused the second and third player.
        UUID installation = UUID.randomUUID();

        GuestRegisterResponse first =
                accountService.registerGuest(uniqueName(), "TR", installation.toString());
        GuestRegisterResponse second =
                accountService.registerGuest(uniqueName(), "TR", installation.toString());

        assertEquals(installation, storedInstallation(first.accountId()));
        assertEquals(installation, storedInstallation(second.accountId()));

        Integer linked = jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE installation_id = ?",
                Integer.class, installation);
        assertEquals(2, linked, "both registrations are linked, neither refused");
    }

    @Test
    void theIndexIsPartialSoHistoricalAccountsAreNotIndexed() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT indexdef FROM pg_indexes "
                        + "WHERE tablename = 'accounts' AND indexname = 'idx_accounts_installation_id'");

        assertEquals(1, rows.size(), "the analytics index exists");
        String def = String.valueOf(rows.get(0).get("indexdef"));
        assertTrue(def.contains("installation_id IS NOT NULL"),
                "partial on NOT NULL, so historical rows stay out of it: " + def);
        assertTrue(!def.startsWith("CREATE UNIQUE"),
                "must not be unique — one install legitimately registers several accounts: " + def);
    }

    @Test
    void theJourneyViewJoinsTelemetryToTheAccountWithoutACast() {
        UUID installation = UUID.randomUUID();
        String name = uniqueName();

        accountService.registerGuest(name, "TR", installation.toString());

        // The view has to survive a non-UUID telemetry row sitting in the same table:
        // onboarding_events.installation_id is VARCHAR(64) precisely so an unauthenticated
        // caller cannot fail the insert, so the view's guarded cast is what stops 22P02.
        jdbc.update("INSERT INTO onboarding_events "
                + "(id, event_id, installation_id, event_name, received_at) "
                + "VALUES (?, ?, ?, 'app_launched', NOW())",
                UUID.randomUUID(), UUID.randomUUID().toString(), installation.toString());
        jdbc.update("INSERT INTO onboarding_events "
                + "(id, event_id, installation_id, event_name, received_at) "
                + "VALUES (?, ?, 'definitely-not-a-uuid', 'app_launched', NOW())",
                UUID.randomUUID(), UUID.randomUUID().toString());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT registered_display_name, reached_app_launched, accounts_registered_here "
                        + "FROM admin_installation_journey WHERE installation_id = ?", installation);

        assertEquals(name, row.get("registered_display_name"));
        assertEquals(Boolean.TRUE, row.get("reached_app_launched"));
        assertEquals(1L, ((Number) row.get("accounts_registered_here")).longValue());
    }
}
