package com.cenk.valocase.battle.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.battle.domain.Battle;

public interface BattleRepository extends JpaRepository<Battle, UUID> {

    List<Battle> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
}
