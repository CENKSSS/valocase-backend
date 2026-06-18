package com.cenk.valocase.battle.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cenk.valocase.battle.domain.BattleLobby;
import com.cenk.valocase.battle.domain.LobbyStatus;

import jakarta.persistence.LockModeType;

public interface BattleLobbyRepository extends JpaRepository<BattleLobby, UUID> {

    /**
     * Loads a lobby with a pessimistic write lock so concurrent join / add-bot /
     * start / cancel actions on the same lobby are serialized — no two callers
     * can fill the same slot or start the battle twice.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from BattleLobby l where l.id = :id")
    Optional<BattleLobby> findByIdForUpdate(@Param("id") UUID id);

    /** Public, still-open lobbies for the lobby browser, newest first. */
    List<BattleLobby> findByStatusOrderByCreatedAtDesc(LobbyStatus status);
}
