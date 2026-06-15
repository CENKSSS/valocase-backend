package com.cenk.valocase.upgrade.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.upgrade.domain.UpgradeInput;

public interface UpgradeInputRepository extends JpaRepository<UpgradeInput, UUID> {

    List<UpgradeInput> findByUpgradeId(UUID upgradeId);
}
