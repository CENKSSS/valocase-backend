package com.cenk.valocase.wallet.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.repository.WalletRepository;
import com.cenk.valocase.wallet.repository.WalletTransactionRepository;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    @Mock private WalletRepository walletRepository;
    @Mock private WalletTransactionRepository walletTransactionRepository;

    private static final UUID ACCOUNT = UUID.randomUUID();

    private WalletService service;

    @BeforeEach
    void setUp() {
        service = new WalletService(walletRepository, walletTransactionRepository);
    }

    private Wallet walletWithDiamonds(long diamonds) {
        Wallet wallet = new Wallet();
        wallet.setAccountId(ACCOUNT);
        wallet.setDiamondBalance(diamonds);
        return wallet;
    }

    @Test
    void creditDiamonds_addsAndPersists() {
        Wallet wallet = walletWithDiamonds(3L);
        when(walletRepository.findByAccountId(ACCOUNT)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Wallet result = service.creditDiamonds(ACCOUNT, 1L);

        assertEquals(4L, result.getDiamondBalance());
        verify(walletRepository).save(wallet);
    }

    @Test
    void debitDiamonds_subtracts() {
        Wallet wallet = walletWithDiamonds(10L);
        when(walletRepository.findByAccountId(ACCOUNT)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Wallet result = service.debitDiamonds(ACCOUNT, 4L);

        assertEquals(6L, result.getDiamondBalance());
    }

    @Test
    void debitDiamonds_exactBalance_reachesZeroNotNegative() {
        Wallet wallet = walletWithDiamonds(2L);
        when(walletRepository.findByAccountId(ACCOUNT)).thenReturn(Optional.of(wallet));
        when(walletRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        Wallet result = service.debitDiamonds(ACCOUNT, 2L);

        assertEquals(0L, result.getDiamondBalance());
    }

    @Test
    void debitDiamonds_insufficient_throwsAndDoesNotPersist() {
        Wallet wallet = walletWithDiamonds(3L);
        when(walletRepository.findByAccountId(ACCOUNT)).thenReturn(Optional.of(wallet));

        InsufficientDiamondsException ex = assertThrows(InsufficientDiamondsException.class,
                () -> service.debitDiamonds(ACCOUNT, 5L));

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatus());
        assertEquals(3L, wallet.getDiamondBalance());
        verify(walletRepository, never()).save(any());
    }
}
