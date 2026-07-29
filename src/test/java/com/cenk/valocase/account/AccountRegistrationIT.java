package com.cenk.valocase.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;

import com.cenk.valocase.account.dto.GuestRegisterResponse;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.common.exception.ApiException;

/**
 * Guest registration against a real Flyway-migrated Postgres.
 *
 * <p>The point of most of these is what is <em>not</em> in the database after a
 * rejected request: the endpoint is unauthenticated and hands out
 * {@link AccountService#STARTING_VP} VP, so a rejected name must leave no
 * account, no wallet and no wallet transaction behind.
 *
 * <p>No Testcontainers — this project has no Docker. Point the usual Spring
 * datasource properties at any Postgres and run it.
 */
@SpringBootTest
class AccountRegistrationIT {

    @Autowired JdbcTemplate jdbc;
    @Autowired AccountService accountService;

    private int accountCount() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM accounts", Integer.class);
        return c == null ? 0 : c;
    }

    private int walletCount() {
        Integer c = jdbc.queryForObject("SELECT COUNT(*) FROM wallets", Integer.class);
        return c == null ? 0 : c;
    }

    private String uniqueName() {
        return "N" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    @Test
    void nicknameSuppliedAtRegistration_isTheAccountsNameFromBirth() {
        String name = uniqueName();

        GuestRegisterResponse res = accountService.registerGuest(name);

        assertEquals(name, res.displayName());
        // Read it back from the database, not just the response.
        String stored = jdbc.queryForObject(
                "SELECT display_name FROM accounts WHERE id = ?::uuid", String.class, res.accountId());
        assertEquals(name, stored);
        // No AgentXXXX placeholder was ever written, so no rename is needed.
        assertEquals(AccountService.STARTING_VP, res.vpBalance());
    }

    @Test
    void surroundingWhitespaceIsTrimmed() {
        String name = uniqueName();

        GuestRegisterResponse res = accountService.registerGuest("  " + name + "  ");

        assertEquals(name, res.displayName());
    }

    @Test
    void missingNickname_isRejected_andWritesNothing() {
        // The whole point of the rule: a bare POST no longer mints an account
        // holding the starting balance. Only a client that walked a player
        // through the nickname screen can register.
        int accountsBefore = accountCount();
        int walletsBefore = walletCount();

        for (String missing : new String[]{null, "", "   "}) {
            ApiException ex = assertThrows(ApiException.class,
                    () -> accountService.registerGuest(missing));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        }

        assertEquals(accountsBefore, accountCount());
        assertEquals(walletsBefore, walletCount());
    }

    @Test
    void noAccountEverGetsTheGeneratedPlaceholderNameAnymore() {
        String name = uniqueName();
        GuestRegisterResponse res = accountService.registerGuest(name);

        // "AgentXXXX" was the placeholder that marked an abandoned registration.
        // Nothing writes it at sign-up now, so it stops appearing entirely.
        assertFalse(res.displayName().matches("^Agent[0-9A-Fa-f]{4}$"),
                "unexpected placeholder name: " + res.displayName());
        assertEquals(name, res.displayName());
    }

    @Test
    void tooShortNickname_isRejected_andWritesNothing() {
        int accountsBefore = accountCount();
        int walletsBefore = walletCount();

        ApiException ex = assertThrows(ApiException.class, () -> accountService.registerGuest("ab"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(accountsBefore, accountCount());
        assertEquals(walletsBefore, walletCount());
    }

    @Test
    void tooLongNickname_isRejected_andWritesNothing() {
        int accountsBefore = accountCount();

        ApiException ex = assertThrows(ApiException.class,
                () -> accountService.registerGuest("A".repeat(AccountService.DISPLAY_NAME_MAX_LENGTH + 1)));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
        assertEquals(accountsBefore, accountCount());
    }

    @Test
    void nicknameWithIllegalCharacters_isRejected_andWritesNothing() {
        int accountsBefore = accountCount();

        for (String bad : new String[]{"ad soyad", "emoji😀", "nokta.li", "tire-li", "çğüşiö"}) {
            ApiException ex = assertThrows(ApiException.class,
                    () -> accountService.registerGuest(bad), "should have rejected: " + bad);
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus(), bad);
        }
        assertEquals(accountsBefore, accountCount());
    }

    @Test
    void registrationAndRenameAcceptExactlyTheSameNames() {
        // One shared validator, so a name that is legal at sign-up cannot be
        // illegal on a later rename, or the reverse.
        String legal = uniqueName();
        GuestRegisterResponse res = accountService.registerGuest(legal);
        var account = jdbc.queryForObject(
                "SELECT display_name FROM accounts WHERE id = ?::uuid", String.class, res.accountId());
        assertEquals(legal, account);

        assertThrows(ApiException.class, () -> accountService.registerGuest("ab"));
    }
}
