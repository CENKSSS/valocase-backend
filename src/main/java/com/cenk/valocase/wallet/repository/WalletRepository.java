package com.cenk.valocase.wallet.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.wallet.domain.Wallet;

public interface WalletRepository extends JpaRepository<Wallet, UUID> {

    Optional<Wallet> findByAccountId(UUID accountId);
}
