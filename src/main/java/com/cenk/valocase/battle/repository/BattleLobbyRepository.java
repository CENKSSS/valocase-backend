package com.cenk.valocase.battle.repository;

import java.time.Instant;
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

    /**
     * Candidate lobbies for the public browser, newest first. Creator-only
     * lobbies older than the stale window are filtered out in the service (a
     * lobby that another real player has joined keeps showing past the window).
     */
    List<BattleLobby> findByStatusOrderByCreatedAtDesc(LobbyStatus status);

    /**
     * Stale lobbies for the cleanup job. Player-created and event lobbies expire
     * on different windows, so each kind is matched against its own cutoff in one
     * query — no lobby is picked up before it is genuinely stale.
     */
    @Query("""
            select l from BattleLobby l
            where l.status = :status
              and ((l.event = false and l.createdAt < :playerCutoff)
                or (l.event = true and l.createdAt < :eventCutoff))
            """)
    List<BattleLobby> findStaleByStatus(@Param("status") LobbyStatus status,
                                        @Param("playerCutoff") Instant playerCutoff,
                                        @Param("eventCutoff") Instant eventCutoff);

    /** Full lobbies whose start delay has elapsed but were never resolved by a poll. */
    List<BattleLobby> findByStatusAndReadyAtLessThanEqual(LobbyStatus status, Instant readyAtMax);

    /** True once an event lobby for this window exists (any status). */
    boolean existsByEventWindowKey(String eventWindowKey);

    /**
     * When the most recent Free Lobby Event was created, in any status — this is
     * what the next event's cadence is measured from. Empty until the first event
     * lobby has ever been created.
     */
    @Query("select max(l.createdAt) from BattleLobby l where l.event = true")
    Optional<Instant> latestEventLobbyCreatedAt();
}
