package com.cenk.valocase.account.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.domain.AccountStatus;
import com.cenk.valocase.account.dto.AccountProfileResponse;
import com.cenk.valocase.account.dto.GuestRegisterResponse;
import com.cenk.valocase.account.repository.AccountRepository;
import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.service.WalletService;

import lombok.RequiredArgsConstructor;

/**
 * Guest account creation and token-based resolution. No passwords or external
 * login in Phase 1 — the guestToken is the only credential.
 */
@Service
@RequiredArgsConstructor
public class AccountService {

    /** Starting VP balance granted to every new guest. */
    public static final long STARTING_VP = 10_000L;

    /** Minimum length of a player-chosen display name. */
    public static final int DISPLAY_NAME_MIN_LENGTH = 3;
    /** Maximum length of a player-chosen display name. */
    public static final int DISPLAY_NAME_MAX_LENGTH = 20;

    private static final java.util.regex.Pattern DISPLAY_NAME_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z0-9_]+$");

    private final AccountRepository accountRepository;
    private final WalletService walletService;

    @Transactional
    public GuestRegisterResponse registerGuest() {
        Instant now = Instant.now();

        Account account = new Account();
        account.setGuestToken(UUID.randomUUID());
        account.setStatus(AccountStatus.ACTIVE);
        account.setCreatedAt(now);
        account.setLastSeenAt(now);
        account = accountRepository.save(account);
        account.setDisplayName(defaultDisplayName(account.getId()));
        account = accountRepository.save(account);

        Wallet wallet = walletService.createInitialWallet(account.getId(), STARTING_VP);

        return new GuestRegisterResponse(
                account.getId().toString(),
                account.getGuestToken().toString(),
                account.getDisplayName(),
                account.getStatus().name(),
                wallet.getVpBalance()
        );
    }

    @Transactional
    public AccountProfileResponse updateDisplayName(Account account, String rawDisplayName) {
        if (rawDisplayName == null || rawDisplayName.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "displayName is required");
        }
        String trimmed = rawDisplayName.trim();
        if (trimmed.length() < DISPLAY_NAME_MIN_LENGTH || trimmed.length() > DISPLAY_NAME_MAX_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "displayName must be between " + DISPLAY_NAME_MIN_LENGTH
                            + " and " + DISPLAY_NAME_MAX_LENGTH + " characters");
        }
        if (!DISPLAY_NAME_PATTERN.matcher(trimmed).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "displayName may only contain letters, numbers and underscore");
        }
        account.setDisplayName(trimmed);
        accountRepository.save(account);
        return new AccountProfileResponse(account.getId().toString(), account.getDisplayName());
    }

    /** Default name for a fresh account: "Agent" + 4 stable chars from the account id. */
    public static String defaultDisplayName(UUID accountId) {
        String suffix = accountId.toString().replace("-", "").substring(0, 4).toUpperCase();
        return "Agent" + suffix;
    }

    /**
     * The name to show for a real player: their chosen display name, or a stable
     * per-account fallback when none was set. Never returns null/blank.
     */
    public static String resolveDisplayName(String displayName, UUID accountId) {
        if (displayName != null && !displayName.isBlank()) {
            return displayName.trim();
        }
        if (accountId == null) {
            return "Oyuncu";
        }
        return defaultDisplayName(accountId);
    }

    /**
     * Resolves the account behind a raw {@code X-Guest-Token} header value,
     * touching its lastSeenAt. Throws 401 if the header is missing, malformed,
     * or does not match an active account.
     */
    @Transactional
    public Account requireAccountByToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Missing X-Guest-Token header");
        }

        UUID token;
        try {
            token = UUID.fromString(rawToken.trim());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Invalid X-Guest-Token");
        }

        Account account = accountRepository.findByGuestToken(token)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Invalid X-Guest-Token"));

        if (account.getStatus() != AccountStatus.ACTIVE) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Account is not active");
        }

        account.setLastSeenAt(Instant.now());
        return account;
    }
}
