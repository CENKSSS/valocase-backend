package com.cenk.valocase.market.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import com.cenk.valocase.common.exception.ApiException;
import com.cenk.valocase.market.dto.MarketPurchaseResponse;
import com.cenk.valocase.wallet.domain.Wallet;
import com.cenk.valocase.wallet.service.InsufficientDiamondsException;
import com.cenk.valocase.wallet.service.WalletService;

@ExtendWith(MockitoExtension.class)
class MarketServiceTest {

    @Mock private WalletService walletService;

    private static final UUID ACCOUNT = UUID.randomUUID();

    private MarketService service;

    @BeforeEach
    void setUp() {
        service = new MarketService(new MarketCatalog(), walletService);
    }

    private Wallet wallet(long vp, long diamonds) {
        Wallet wallet = new Wallet();
        wallet.setVpBalance(vp);
        wallet.setDiamondBalance(diamonds);
        return wallet;
    }

    @Test
    void purchaseVp_subtractsDiamonds_andAddsVp() {
        when(walletService.credit(eq(ACCOUNT), eq(1_000L), eq(MarketService.REASON_VP_EXCHANGE), any()))
                .thenReturn(wallet(11_000L, 80L));

        MarketPurchaseResponse r = service.purchaseVp(ACCOUNT, "vp_1000");

        assertEquals("vp_1000", r.offerId());
        assertEquals(1_000L, r.vpGranted());
        assertEquals(20L, r.diamondCost());
        assertEquals(11_000L, r.vpBalance());
        assertEquals(80L, r.diamondBalance());
        verify(walletService).debitDiamonds(ACCOUNT, 20L);
        verify(walletService).credit(eq(ACCOUNT), eq(1_000L), eq(MarketService.REASON_VP_EXCHANGE), any());
    }

    @Test
    void purchaseVp_verifiesCostServerSide_for700Pack() {
        when(walletService.credit(eq(ACCOUNT), eq(50_000L), any(), any()))
                .thenReturn(wallet(50_000L, 0L));

        MarketPurchaseResponse r = service.purchaseVp(ACCOUNT, "vp_50000");

        assertEquals(700L, r.diamondCost());
        verify(walletService).debitDiamonds(ACCOUNT, 700L);
    }

    @Test
    void purchaseVp_insufficientDiamonds_rejected_andNoVpCredited() {
        doThrow(new InsufficientDiamondsException(5L, 375L))
                .when(walletService).debitDiamonds(ACCOUNT, 375L);

        assertThrows(InsufficientDiamondsException.class,
                () -> service.purchaseVp(ACCOUNT, "vp_25000"));

        verify(walletService, never()).credit(any(), anyLong(), any(), any());
    }

    @Test
    void purchaseVp_unknownOffer_rejected() {
        ApiException ex = assertThrows(ApiException.class, () -> service.purchaseVp(ACCOUNT, "vp_999"));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
        verify(walletService, never()).debitDiamonds(any(), anyLong());
    }
}
