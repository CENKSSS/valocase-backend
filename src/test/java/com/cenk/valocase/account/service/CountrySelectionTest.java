package com.cenk.valocase.account.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import com.cenk.valocase.account.RegistrationProperties;
import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.domain.AccountStatus;
import com.cenk.valocase.account.dto.AccountProfileResponse;
import com.cenk.valocase.account.dto.GuestRegisterResponse;
import com.cenk.valocase.account.repository.AccountRepository;
import com.cenk.valocase.analytics.service.PlayerActivityService;
import com.cenk.valocase.common.diagnostics.DiagnosticCounters;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.service.WalletService;

/**
 * Country selection at registration and afterwards, without a database.
 *
 * <p>The behaviour that matters here is the rollout switch: with
 * {@code require-country-code} off a client that knows nothing about countries
 * must still be able to register, and with it on the same request must be
 * refused. Both are asserted rather than reasoned about, because getting the
 * first one wrong takes every install in the store offline.
 */
class CountrySelectionTest {

    private AccountRepository accountRepository;
    private WalletService walletService;
    private DiagnosticCounters counters;
    private RegistrationProperties properties;
    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        walletService = mock(WalletService.class);
        PlayerActivityService playerActivityService = mock(PlayerActivityService.class);
        counters = new DiagnosticCounters();
        properties = new RegistrationProperties();
        accountService = new AccountService(accountRepository, walletService,
                playerActivityService, counters, properties);

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

    private static Account existingAccount(String countryCode) {
        Account account = new Account();
        account.setId(UUID.randomUUID());
        account.setGuestToken(UUID.randomUUID());
        account.setDisplayName("Cenk");
        account.setCountryCode(countryCode);
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(Instant.now());
        account.setLastSeenAt(Instant.now());
        return account;
    }

    // --- registration ---------------------------------------------------------

    @Test
    void everyOfficialCodeIsAcceptedAndStoredUppercase() {
        for (String code : new String[]{"TR", "tr", "IN", "PK", "DZ", "US", "GB", "DE", "FR", "JP", "KR"}) {
            AccountRepository fresh = mock(AccountRepository.class);
            when(fresh.save(any(Account.class))).thenAnswer(inv -> {
                Account a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });
            AccountService service = new AccountService(fresh, walletService,
                    mock(PlayerActivityService.class), new DiagnosticCounters(), properties);

            GuestRegisterResponse response = service.registerGuest("Cenk", code);

            String expected = code.toUpperCase(java.util.Locale.ROOT);
            assertEquals(expected, response.countryCode(), code);
            ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
            verify(fresh).save(saved.capture());
            assertEquals(expected, saved.getValue().getCountryCode(), code);
        }
    }

    @Test
    void aLowercaseCodeIsUppercasedEverywhereItAppears() {
        GuestRegisterResponse response = accountService.registerGuest("Cenk", "tr");

        assertEquals("TR", response.countryCode(), "response");
        assertEquals("TR", savedAccount().getCountryCode(), "stored");
    }

    @Test
    void surroundingWhitespaceOnTheCodeIsTrimmed() {
        assertEquals("DZ", accountService.registerGuest("Cenk", "  dz  ").countryCode());
    }

    @Test
    void anInvalidCountryIsRefusedAndWritesNothing() {
        for (String bad : new String[]{"TUR", "Turkey", "Türkiye", "India", "123", "T1", "ZZ", "XX", "UK"}) {
            AccountRepository fresh = mock(AccountRepository.class);
            AccountService service = new AccountService(fresh, walletService,
                    mock(PlayerActivityService.class), new DiagnosticCounters(), properties);

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.registerGuest("Cenk", bad), "should have rejected: " + bad);

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus(), bad);
            verify(fresh, never()).save(any(Account.class));
        }
    }

    @Test
    void anInvalidCountryIsRefusedEvenWhileTheCountryIsStillOptional() {
        // Optional means "may be absent", never "may be wrong". A client sending
        // "Türkiye" is broken rather than old, and storing null for it would hide
        // exactly the bug this rollout needs to catch early.
        properties.setRequireCountryCode(false);

        assertThrows(ApiException.class, () -> accountService.registerGuest("Cenk", "Türkiye"));
        verify(accountRepository, never()).save(any(Account.class));
        assertEquals(1L, counters.rejectionsByReason().get("COUNTRY_INVALID"));
    }

    // --- the migration window -------------------------------------------------

    @Test
    void theOldClientStillRegistersWhileTheCountryIsOptional() {
        // The build in the store sends displayName alone. If this ever fails,
        // shipping the backend stops every install in the world from signing up.
        properties.setRequireCountryCode(false);

        GuestRegisterResponse response = accountService.registerGuest("Cenk", null);

        assertNull(response.countryCode());
        assertNull(savedAccount().getCountryCode(), "no country may be invented for it");
        assertEquals(AccountService.STARTING_VP, response.vpBalance());
        assertEquals(1L, counters.asMap().get("guest_registration_success"));
    }

    @Test
    void aBlankCountryIsTreatedAsAbsentRatherThanAsAValue() {
        properties.setRequireCountryCode(false);

        for (String blank : new String[]{"", "   ", "\t"}) {
            AccountRepository fresh = mock(AccountRepository.class);
            when(fresh.save(any(Account.class))).thenAnswer(inv -> {
                Account a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });
            AccountService service = new AccountService(fresh, walletService,
                    mock(PlayerActivityService.class), new DiagnosticCounters(), properties);

            assertNull(service.registerGuest("Cenk", blank).countryCode(), "[" + blank + "]");
        }
    }

    @Test
    void onceTheSwitchIsOnTheSameRequestIsRefusedAndWritesNothing() {
        properties.setRequireCountryCode(true);

        for (String missing : new String[]{null, "", "   "}) {
            ApiException ex = assertThrows(ApiException.class,
                    () -> accountService.registerGuest("Cenk", missing));
            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
            assertTrue(ex.getMessage().contains("countryCode"), ex.getMessage());
        }

        verify(accountRepository, never()).save(any(Account.class));
        verify(walletService, never()).createInitialWallet(any(UUID.class), anyLong());
        assertEquals(3L, counters.rejectionsByReason().get("COUNTRY_MISSING"));
    }

    @Test
    void withTheSwitchOnAValidCountryStillRegistersNormally() {
        properties.setRequireCountryCode(true);

        assertEquals("PK", accountService.registerGuest("Cenk", "pk").countryCode());
    }

    // --- display-name behaviour is untouched ----------------------------------

    @Test
    void unicodeDisplayNamesStillWorkAlongsideACountry() {
        for (String name : new String[]{"Yiğit", "Çınar", "محمد", "अर्जुन", "한국어"}) {
            AccountRepository fresh = mock(AccountRepository.class);
            when(fresh.save(any(Account.class))).thenAnswer(inv -> {
                Account a = inv.getArgument(0);
                a.setId(UUID.randomUUID());
                return a;
            });
            AccountService service = new AccountService(fresh, walletService,
                    mock(PlayerActivityService.class), new DiagnosticCounters(), properties);

            GuestRegisterResponse response = service.registerGuest(name, "tr");

            assertEquals(name, response.displayName(), name);
            assertEquals("TR", response.countryCode(), name);
        }
    }

    @Test
    void aBadNicknameIsStillRejectedAsANicknameNotAsACountry() {
        // Order matters for the reason code: the nickname is validated first, so
        // a request that is wrong in both ways reports the nickname rule and the
        // country vocabulary stays out of the display-name counters.
        properties.setRequireCountryCode(true);

        assertThrows(ApiException.class, () -> accountService.registerGuest("ab", null));

        assertEquals(1L, counters.rejectionsByReason().get("TOO_SHORT"));
        assertNull(counters.rejectionsByReason().get("COUNTRY_MISSING"));
    }

    @Test
    void countryReasonsAreNeverAddedToTheDisplayNameVocabulary() {
        // Telemetry maps nickname_rejected strictly onto RegistrationRejectionReason.
        // A country cause added to that enum would become an accepted nickname
        // reason and quietly corrupt the rejection breakdown.
        for (RegistrationRejectionReason reason : RegistrationRejectionReason.values()) {
            assertTrue(!reason.name().contains("COUNTRY"), reason.name());
        }
    }

    // --- changing the country later -------------------------------------------

    @Test
    void theCountryCanBeChangedLaterAndIsUppercased() {
        Account account = existingAccount("TR");

        AccountProfileResponse response = accountService.updateCountryCode(account, "in");

        assertEquals("IN", response.countryCode());
        assertEquals("IN", account.getCountryCode());
        assertEquals(account.getId().toString(), response.accountId());
        assertEquals("Cenk", response.displayName(), "the rename is not part of a country change");
    }

    @Test
    void anAccountThatNeverHadACountryCanSetOne() {
        // Everyone who registered before the country screen shipped. Settings is
        // how their row stops being NULL, so this path has to work on null.
        Account account = existingAccount(null);

        assertEquals("DZ", accountService.updateCountryCode(account, "dz").countryCode());
        assertEquals("DZ", account.getCountryCode());
    }

    @Test
    void aRefusedChangeLeavesThePreviousCountryExactlyAsItWas() {
        for (String bad : new String[]{"TUR", "Türkiye", "ZZ", "123", "T1", null, "", "   "}) {
            Account account = existingAccount("TR");
            AccountRepository fresh = mock(AccountRepository.class);
            AccountService service = new AccountService(fresh, walletService,
                    mock(PlayerActivityService.class), new DiagnosticCounters(), properties);

            ApiException ex = assertThrows(ApiException.class,
                    () -> service.updateCountryCode(account, bad), String.valueOf(bad));

            assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus(), String.valueOf(bad));
            assertEquals("TR", account.getCountryCode(), "old country lost on: " + bad);
            verify(fresh, never()).save(any(Account.class));
        }
    }

    @Test
    void aBlankChangeDoesNotClearTheCountry() {
        // The picker cannot produce "no country", so a blank arriving here is a
        // bug in the client. Treating it as "clear it" would silently undo a
        // selection the player made.
        Account account = existingAccount("US");

        assertThrows(ApiException.class, () -> accountService.updateCountryCode(account, ""));
        assertEquals("US", account.getCountryCode());
    }

    @Test
    void changingTheCountryTouchesOnlyTheAccountItWasGiven() {
        Account mine = existingAccount("TR");
        Account other = existingAccount("US");

        accountService.updateCountryCode(mine, "GB");

        assertEquals("GB", mine.getCountryCode());
        assertEquals("US", other.getCountryCode(), "another account must be untouched");
    }
}
