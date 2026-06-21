package com.cenk.valocase.earnvp.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.earnvp.domain.EarnVpSession;

public interface EarnVpSessionRepository extends JpaRepository<EarnVpSession, UUID> {

    Optional<EarnVpSession> findByIdAndAccountId(UUID id, UUID accountId);
}
