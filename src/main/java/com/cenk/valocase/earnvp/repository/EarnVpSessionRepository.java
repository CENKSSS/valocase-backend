package com.cenk.valocase.earnvp.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.cenk.valocase.earnvp.domain.EarnVpSession;

public interface EarnVpSessionRepository extends JpaRepository<EarnVpSession, UUID> {

    Optional<EarnVpSession> findByIdAndAccountId(UUID id, UUID accountId);

    Optional<EarnVpSession> findTopByAccountIdOrderByCreatedAtDesc(UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from EarnVpSession s where s.id = :id and s.accountId = :accountId")
    Optional<EarnVpSession> findByIdAndAccountIdForUpdate(@Param("id") UUID id, @Param("accountId") UUID accountId);
}
