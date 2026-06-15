package com.cenk.valocase.account.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.account.domain.Account;
import com.cenk.valocase.account.domain.AccountStatus;
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

        Wallet wallet = walletService.createInitialWallet(account.getId(), STARTING_VP);

        return new GuestRegisterResponse(
                account.getId().toString(),
                account.getGuestToken().toString(),
                account.getDisplayName(),
                account.getStatus().name(),
                wallet.getVpBalance()
        );
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
