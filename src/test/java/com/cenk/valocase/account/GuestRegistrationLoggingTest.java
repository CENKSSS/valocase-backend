package com.cenk.valocase.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;

import com.cenk.valocase.account.RegistrationProperties;
import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.dto.GuestRegisterResponse;
import com.cenk.valocase.account.repository.AccountRepository;
import com.cenk.valocase.account.service.AccountService;
import com.cenk.valocase.analytics.service.PlayerActivityService;
import com.cenk.valocase.common.diagnostics.DiagnosticCounters;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.service.WalletService;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * What guest registration writes to the log, and — just as importantly — what it
 * must never write there.
 *
 * <p>No database: the point is the log and counter behaviour around the service,
 * so the repository and wallet are mocked.
 */
class GuestRegistrationLoggingTest {

    private AccountRepository accountRepository;
    private WalletService walletService;
    private PlayerActivityService playerActivityService;
    private DiagnosticCounters counters;
    private AccountService accountService;

    private Logger serviceLogger;
    private ListAppender<ILoggingEvent> appender;

    private UUID savedAccountId;

    @BeforeEach
    void setUp() {
        accountRepository = mock(AccountRepository.class);
        walletService = mock(WalletService.class);
        playerActivityService = mock(PlayerActivityService.class);
        counters = new DiagnosticCounters();
        accountService = new AccountService(accountRepository, walletService,
                playerActivityService, counters, new RegistrationProperties());

        savedAccountId = UUID.randomUUID();
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> {
            Account account = invocation.getArgument(0);
            account.setId(savedAccountId);
            return account;
        });
        Wallet wallet = new Wallet();
        wallet.setVpBalance(AccountService.STARTING_VP);
        when(walletService.createInitialWallet(any(UUID.class), anyLong())).thenReturn(wallet);

        serviceLogger = (Logger) LoggerFactory.getLogger(AccountService.class);
        appender = new ListAppender<>();
        appender.start();
        serviceLogger.addAppender(appender);
        serviceLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        serviceLogger.detachAppender(appender);
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private boolean logged(String fragment) {
        return messages().stream().anyMatch(message -> message.contains(fragment));
    }

    @Test
    void successLogsStartAndCreationWithTheAccountId() {
        GuestRegisterResponse response = accountService.registerGuest("Cenk", "TR");

        assertTrue(logged("guest registration started"), messages().toString());
        assertTrue(logged("guest registration created: accountId=" + savedAccountId), messages().toString());
        assertEquals(1L, counters.asMap().get("guest_registration_started"));
        assertEquals(1L, counters.asMap().get("guest_registration_success"));
        assertEquals(0L, counters.asMap().get("guest_registration_rejected"));
        assertEquals(savedAccountId.toString(), response.accountId());
    }

    @Test
    void theGuestTokenIsNeverLogged() {
        GuestRegisterResponse response = accountService.registerGuest("Cenk", "TR");

        // The token is the only credential in the system. It must not reach a log
        // sink, whether in the success line or anywhere else.
        String token = response.guestToken();
        assertFalse(messages().stream().anyMatch(message -> message.contains(token)),
                "guest token leaked into a log line: " + messages());
    }

    @Test
    void theNicknameIsNeverLogged() {
        // A nickname is user-supplied content. Only its length and the rule it
        // broke are diagnostic; the text itself is not ours to write down.
        accountService.registerGuest("Cenk", "TR");
        assertThrows(ApiException.class, () -> accountService.registerGuest("Ahmet Yılmaz", "TR"));

        assertFalse(logged("Cenk"), messages().toString());
        assertFalse(logged("Ahmet"), messages().toString());
        assertFalse(logged("Yılmaz"), messages().toString());
    }

    @Test
    void blankIsLoggedAndCountedAsBlank() {
        assertThrows(ApiException.class, () -> accountService.registerGuest(null, "TR"));

        assertTrue(logged("guest registration rejected: reason=BLANK"), messages().toString());
        assertEquals(1L, counters.asMap().get("guest_registration_rejected"));
        assertEquals(1L, counters.rejectionsByReason().get("BLANK"));
    }

    @Test
    void tooShortAndTooLongAreLoggedDistinctly() {
        assertThrows(ApiException.class, () -> accountService.registerGuest("ab", "TR"));
        assertThrows(ApiException.class, () -> accountService.registerGuest(
                "A".repeat(AccountService.DISPLAY_NAME_MAX_LENGTH + 1), "TR"));

        assertTrue(logged("guest registration rejected: reason=TOO_SHORT"), messages().toString());
        assertTrue(logged("guest registration rejected: reason=TOO_LONG"), messages().toString());
        assertEquals(1L, counters.rejectionsByReason().get("TOO_SHORT"));
        assertEquals(1L, counters.rejectionsByReason().get("TOO_LONG"));
    }

    @Test
    void illegalCharactersAreLoggedAsInvalidCharacter() {
        assertThrows(ApiException.class, () -> accountService.registerGuest("player!", "TR"));

        assertTrue(logged("guest registration rejected: reason=INVALID_CHARACTER"), messages().toString());
        assertEquals(1L, counters.rejectionsByReason().get("INVALID_CHARACTER"));
    }

    @Test
    void internalWhitespaceIsLoggedAsWhitespace() {
        assertThrows(ApiException.class, () -> accountService.registerGuest("Ahmet Yilmaz", "TR"));

        assertTrue(logged("guest registration rejected: reason=WHITESPACE"), messages().toString());
        assertEquals(1L, counters.rejectionsByReason().get("WHITESPACE"));
    }

    @Test
    void aTurkishNicknameNowRegistersInsteadOfBeingRejected() {
        // The rule this replaced refused every non-ASCII letter. That was the
        // single most likely reason real players could not finish onboarding, so
        // it is pinned here rather than only in the validator's own suite.
        GuestRegisterResponse response = accountService.registerGuest("Çınar", "TR");

        assertEquals("Çınar", response.displayName());
        assertTrue(logged("guest registration created: accountId=" + savedAccountId), messages().toString());
        assertEquals(1L, counters.asMap().get("guest_registration_success"));
    }

    @Test
    void theStoredNameIsTheNormalisedOneThatIsReturned() {
        // Decomposed input: "Jose" + combining acute. What comes back must be the
        // NFC form, and it must be exactly what was handed to the repository.
        GuestRegisterResponse response = accountService.registerGuest("José", "TR");

        assertEquals("José", response.displayName());
        ArgumentCaptor<Account> saved = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(saved.capture());
        assertEquals(response.displayName(), saved.getValue().getDisplayName());
    }

    @Test
    void aRejectedRegistrationNeverTouchesTheDatabase() {
        assertThrows(ApiException.class, () -> accountService.registerGuest("ab", "TR"));

        verify(accountRepository, never()).save(any(Account.class));
        verify(walletService, never()).createInitialWallet(any(UUID.class), anyLong());
    }

    @Test
    void duplicateDisplayNamesRemainLegal() {
        // There is no unique constraint on display_name and none is wanted: two
        // players may share a nickname. Both registrations succeed.
        accountService.registerGuest("Cenk", "TR");
        accountService.registerGuest("Cenk", "TR");

        assertEquals(2L, counters.asMap().get("guest_registration_success"));
        assertEquals(0L, counters.asMap().get("guest_registration_rejected"));
        verify(accountRepository, org.mockito.Mockito.times(2)).save(any(Account.class));
    }

    @Test
    void anUnexpectedIntegrityErrorIsNotRelabelledAsARejection() {
        // Nothing on this path is expected to violate a constraint, so if one
        // does it is a real fault and must surface as such rather than being
        // counted as a validation rejection.
        DataIntegrityViolationException violation = new DataIntegrityViolationException("unexpected");
        when(walletService.createInitialWallet(any(UUID.class), anyLong())).thenThrow(violation);

        DataIntegrityViolationException thrown = assertThrows(DataIntegrityViolationException.class,
                () -> accountService.registerGuest("Cenk", "TR"));

        assertEquals(violation, thrown);
        assertEquals(0L, counters.asMap().get("guest_registration_rejected"));
        assertEquals(0L, counters.asMap().get("guest_registration_success"));
    }

    @Test
    void registrationHasNoDependencyOnTelemetryAtAll() {
        // "Telemetry failure must not affect registration" is guaranteed here by
        // structure rather than by a try/catch: AccountService cannot call the
        // telemetry service because it does not hold one. Asserted rather than
        // assumed, so a future refactor that wires them together fails loudly.
        for (var field : AccountService.class.getDeclaredFields()) {
            assertFalse(field.getType().getName().contains("telemetry"),
                    "registration must not depend on telemetry: " + field.getName());
        }
    }

    @Test
    void everyAttemptIsCountedAsStartedWhicheverWayItEnds() {
        accountService.registerGuest("Cenk", "TR");
        assertThrows(ApiException.class, () -> accountService.registerGuest(null, "TR"));
        assertThrows(ApiException.class, () -> accountService.registerGuest("ab", "TR"));

        // started = success + rejected, so a gap between them in production means
        // requests are dying somewhere other than validation.
        assertEquals(3L, counters.asMap().get("guest_registration_started"));
        assertEquals(1L, counters.asMap().get("guest_registration_success"));
        assertEquals(2L, counters.asMap().get("guest_registration_rejected"));
    }
}
