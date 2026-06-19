package com.cenk.valocase.battle.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.battle.domain.BattleLobbyCase;

public interface BattleLobbyCaseRepository extends JpaRepository<BattleLobbyCase, UUID> {

    List<BattleLobbyCase> findByLobbyIdOrderByOrdinalAsc(UUID lobbyId);

    List<BattleLobbyCase> findByLobbyIdInOrderByOrdinalAsc(Collection<UUID> lobbyIds);
}
