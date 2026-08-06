package com.cenk.valocase.account.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.cenk.valocase.account.RegistrationProperties;
import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.dto.GuestRegisterResponse;
import com.cenk.valocase.account.repository.AccountRepository;
import com.cenk.valocase.analytics.service.PlayerActivityService;
import com.cenk.valocase.common.diagnostics.DiagnosticCounters;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.service.WalletService;

/**
 * The installation -> account link written at registration, without a database.
 *
 * <p>The rule these all defend is one-directional: the install id may be
 * recorded, and it may never cost a registration. Every malformed, blank and
 * absent shape a client can send must still produce an account, because the
 * alternative trades a real player for a measurement. The two live store builds
 * (1.0.19 and 1.0.21) send no id at all, so the "absent" case is not a hypothesis
 * — it is the majority of production traffic on the day this was written.
 */
class InstallationLinkTest {

    private AccountRepository accountRepository;
    private WalletService walletService;
    private RegistrationProperties properties;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        walletService = mock(WalletService.class);
        properties = new RegistrationProperties();
        accountService = new AccountService(accountRepository, walletService,
                mock(PlayerActivityService.class), new DiagnosticCounters(), properties);

        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            if (account.getId() == null) {
                account.setId(UUID.randomUUID());
            }
            return account;
        });
        Wallet wallet = new Wallet();
        wallet.setVpBalance(AccountService.STARTING_VP);
        when(walletService.createInitialWallet(any(UUID.class), anyLong())).thenReturn(wallet);
    }

    private Account savedAccount() {
        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saved.capture());
        return saved.getValue();
    }

    private List<Account> allSavedAccounts() {
        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository, times(2)).save(saved.capture());
        return saved.getAllValues();
    }

    // --- the old-client path, which must not change ---------------------------

    @Test
    void aClientThatSendsNoInstallationIdStillRegisters() {
        GuestRegisterResponse response = accountService.registerGuest("Cenk", "TR");

        assertNotNull(response.accountId(), "account was created");
        assertNull(savedAccount().getInstallationId(), "nothing invented for an older client");
    }

    @Test
    void theTwoArgumentFormIsExactlyTheThreeArgumentFormWithNoId() {
        accountService.registerGuest("Cenk", "TR");

        Account stored = savedAccount();
        assertNull(stored.getInstallationId());
        assertEquals("TR", stored.getCountryCode(), "country handling is untouched");
        assertEquals("Cenk", stored.getDisplayName(), "nickname handling is untouched");
    }

    // --- the new-client path --------------------------------------------------

    @Test
    void aValidInstallationIdIsStoredOnTheAccount() {
        UUID installation = UUID.randomUUID();

        accountService.registerGuest("Cenk", "TR", installation.toString());

        assertEquals(installation, savedAccount().getInstallationId());
    }

    @Test
    void anUppercaseOrPaddedInstallationIdIsAcceptedAndCanonicalised() {
        UUID installation = UUID.randomUUID();
        String awkward = "  " + installation.toString().toUpperCase(Locale.ROOT) + "  ";

        accountService.registerGuest("Cenk", "TR", awkward);

        assertEquals(installation, savedAccount().getInstallationId(),
                "UUID.fromString is case-insensitive; the trim is ours");
    }

    // --- every shape that must be dropped rather than refused -----------------

    @Test
    void aBlankInstallationIdBecomesNullAndDoesNotRefuseTheRegistration() {
        for (String blank : new String[]{"", "   ", "\t", "\n"}) {
            AccountRepository fresh = mock(AccountRepository.class);
            when(fresh.save(any(Account.class))).thenAnswer(inv -> {
                Account a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });
            AccountService service = new AccountService(fresh, walletService,
                    mock(PlayerActivityService.class), new DiagnosticCounters(), properties);

            GuestRegisterResponse response = service.registerGuest("Cenk", "TR", blank);

            assertNotNull(response.accountId(), "[" + blank.strip() + "] still registers");
            ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
            verify(fresh).save(saved.capture());
            assertNull(saved.getValue().getInstallationId(), "[" + blank.strip() + "] stored null");
        }
    }

    @Test
    void aMalformedInstallationIdIsDroppedAndTheAccountIsStillCreated() {
        // Everything a broken or hostile client could plausibly put in the field.
        String[] malformed = {
                "not-a-uuid",
                "12345",
                "0000",
                "550e8400-e29b-41d4-a716",                       // truncated
                "550e8400-e29b-41d4-a716-446655440000-extra",     // overlong
                "550e8400e29b41d4a716446655440000000000",         // no dashes, wrong length
                "'; DROP TABLE accounts; --",
                "<script>alert(1)</script>",
                "../../etc/passwd",
        };

        for (String bad : malformed) {
            AccountRepository fresh = mock(AccountRepository.class);
            when(fresh.save(any(Account.class))).thenAnswer(inv -> {
                Account a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });
            AccountService service = new AccountService(fresh, walletService,
                    mock(PlayerActivityService.class), new DiagnosticCounters(), properties);

            GuestRegisterResponse response = service.registerGuest("Cenk", "TR", bad);

            assertNotNull(response.accountId(), "must still register: " + bad);
            ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
            verify(fresh).save(saved.capture());
            assertNull(saved.getValue().getInstallationId(), "must not store: " + bad);
        }
    }

    @Test
    void aMalformedInstallationIdDoesNotDisturbTheRestOfTheAccount() {
        accountService.registerGuest("Cenk", "tr", "garbage");

        Account stored = savedAccount();
        assertNull(stored.getInstallationId());
        assertEquals("TR", stored.getCountryCode(), "country still normalised");
        assertEquals("Cenk", stored.getDisplayName(), "nickname still stored");
        assertNotNull(stored.getGuestToken(), "token still issued");
    }

    // --- one install, several accounts: the reason there is no UNIQUE ---------

    @Test
    void theSameInstallationMayRegisterMoreThanOneAccount() {
        UUID installation = UUID.randomUUID();

        GuestRegisterResponse first = accountService.registerGuest("Cenk", "TR",
                installation.toString());
        GuestRegisterResponse second = accountService.registerGuest("Ahmet", "TR",
                installation.toString());

        assertNotEquals(first.accountId(), second.accountId(), "two distinct accounts");
        List<Account> stored = allSavedAccounts();
        assertEquals(installation, stored.get(0).getInstallationId());
        assertEquals(installation, stored.get(1).getInstallationId(),
                "the second registration is linked too, not refused");
    }

    // --- the id must not travel back out --------------------------------------

    @Test
    void theInstallationIdIsNeverEchoedInTheResponse() {
        UUID installation = UUID.randomUUID();

        GuestRegisterResponse response = accountService.registerGuest("Cenk", "TR",
                installation.toString());

        assertFalseContains(response.toString(), installation.toString());
    }

    private static void assertFalseContains(String haystack, String needle) {
        if (haystack != null && haystack.contains(needle)) {
            throw new AssertionError("response leaked the installation id: " + haystack);
        }
    }

    // --- the log-safe form ----------------------------------------------------

    @Test
    void theLogFormIsTruncatedAndNeverTheWholeId() {
        UUID installation = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        String logged = AccountService.shortInstallation(installation);

        assertEquals("550e8400", logged);
        assertEquals(8, logged.length(), "eight hex characters, not the whole id");
    }

    @Test
    void theLogFormOfNoInstallationIsAWordNotAnEmptyString() {
        assertEquals("none", AccountService.shortInstallation(null));
    }
}
