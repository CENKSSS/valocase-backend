package com.cenk.valocase.battle.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.battle.domain.BattleLobbySlot;

public interface BattleLobbySlotRepository extends JpaRepository<BattleLobbySlot, UUID> {

    List<BattleLobbySlot> findByLobbyIdOrderBySlotIndexAsc(UUID lobbyId);

    boolean existsByLobbyIdAndAccountId(UUID lobbyId, UUID accountId);
}
