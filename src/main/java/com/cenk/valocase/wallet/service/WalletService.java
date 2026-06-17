package com.cenk.valocase.wallet.service;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.domain.WalletTransaction;
import com.cenk.valocase.wallet.dto.WalletResponse;
import com.cenk.valocase.wallet.repository.WalletRepository;
import com.cenk.valocase.wallet.repository.WalletTransactionRepository;

import lombok.RequiredArgsConstructor;

/**
 * Sole owner of wallet balance mutations. Every balance change goes through
 * {@link #applyDelta} and records a {@link WalletTransaction}.
 */
@Service
@RequiredArgsConstructor
public class WalletService {

    /** Reason recorded for the one-time starting balance grant. */
    public static final String REASON_STARTING_BALANCE = "STARTING_BALANCE";

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;

    /**
     * Creates an empty wallet for an account and grants its starting balance
     * via a normal credit (so the seed grant is itself a transaction).
     */
    @Transactional
    public Wallet createInitialWallet(UUID accountId, long startingBalance) {
        Wallet wallet = new Wallet();
        wallet.setAccountId(accountId);
        wallet.setVpBalance(0L);
        wallet.setUpdatedAt(Instant.now());
        wallet = walletRepository.save(wallet);

        if (startingBalance > 0) {
            applyDelta(wallet, startingBalance, REASON_STARTING_BALANCE, null);
        }
        return wallet;
    }

    @Transactional(readOnly = true)
    public WalletResponse getWalletForAccount(UUID accountId) {
        Wallet wallet = requireWallet(accountId);
        return new WalletResponse(accountId.toString(), wallet.getVpBalance(), wallet.getUpdatedAt(), null);
    }

    /** Adds VP to an account's wallet. {@code amount} must be positive. */
    @Transactional
    public Wallet credit(UUID accountId, long amount, String reason, UUID referenceId) {
        requirePositive(amount);
        Wallet wallet = requireWallet(accountId);
        return applyDelta(wallet, amount, reason, referenceId);
    }

    /**
     * Removes VP from an account's wallet. {@code amount} must be positive and
     * must not exceed the current balance.
     */
    @Transactional
    public Wallet debit(UUID accountId, long amount, String reason, UUID referenceId) {
        requirePositive(amount);
        Wallet wallet = requireWallet(accountId);
        if (wallet.getVpBalance() < amount) {
            throw new InsufficientFundsException(wallet.getVpBalance(), amount);
        }
        return applyDelta(wallet, -amount, reason, referenceId);
    }

    private Wallet applyDelta(Wallet wallet, long delta, String reason, UUID referenceId) {
        long newBalance = wallet.getVpBalance() + delta;
        wallet.setVpBalance(newBalance);
        wallet.setUpdatedAt(Instant.now());
        wallet = walletRepository.save(wallet);

        WalletTransaction tx = new WalletTransaction();
        tx.setWalletId(wallet.getId());
        tx.setDelta(delta);
        tx.setBalanceAfter(newBalance);
        tx.setReason(reason);
        tx.setReferenceId(referenceId);
        tx.setCreatedAt(Instant.now());
        walletTransactionRepository.save(tx);

        return wallet;
    }

    private Wallet requireWallet(UUID accountId) {
        return walletRepository.findByAccountId(accountId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Wallet not found for account: " + accountId));
    }

    private void requirePositive(long amount) {
        if (amount <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Amount must be positive: " + amount);
        }
    }
}
