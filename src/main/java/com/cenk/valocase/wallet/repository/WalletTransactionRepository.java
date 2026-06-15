package com.cenk.valocase.wallet.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.wallet.domain.WalletTransaction;

public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, UUID> {

    List<WalletTransaction> findByWalletIdOrderByCreatedAtAsc(UUID walletId);
}
