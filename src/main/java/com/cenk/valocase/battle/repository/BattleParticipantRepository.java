package com.cenk.valocase.battle.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.battle.domain.BattleParticipant;

public interface BattleParticipantRepository extends JpaRepository<BattleParticipant, UUID> {

    List<BattleParticipant> findByBattleIdOrderByParticipantIndexAsc(UUID battleId);
}
