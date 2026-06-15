package com.cenk.valocase.account.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.account.domain.Account;

public interface AccountRepository extends JpaRepository<Account, UUID> {

    Optional<Account> findByGuestToken(UUID guestToken);
}
