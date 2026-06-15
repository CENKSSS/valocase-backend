package com.cenk.valocase.battle.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.battle.domain.BattleRoll;

public interface BattleRollRepository extends JpaRepository<BattleRoll, UUID> {

    List<BattleRoll> findByBattleId(UUID battleId);
}
