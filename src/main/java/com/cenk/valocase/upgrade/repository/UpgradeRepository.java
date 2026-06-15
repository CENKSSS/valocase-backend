package com.cenk.valocase.upgrade.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.upgrade.domain.Upgrade;

public interface UpgradeRepository extends JpaRepository<Upgrade, UUID> {

    List<Upgrade> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
}
