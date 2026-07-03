package com.cenk.valocase.battle.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.battle.domain.BattleLobbySlot;

public interface BattleLobbySlotRepository extends JpaRepository<BattleLobbySlot, UUID> {

    List<BattleLobbySlot> findByLobbyIdOrderBySlotIndexAsc(UUID lobbyId);

    /** Batched slot lookup for the public lobby list, avoiding a per-lobby query. */
    List<BattleLobbySlot> findByLobbyIdInOrderBySlotIndexAsc(Collection<UUID> lobbyIds);

    boolean existsByLobbyIdAndAccountId(UUID lobbyId, UUID accountId);
}
