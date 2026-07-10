package com.cenk.valocase.analytics.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cenk.valocase.analytics.domain.PlayerSession;

import jakarta.persistence.LockModeType;

public interface PlayerSessionRepository extends JpaRepository<PlayerSession, UUID> {

    Optional<PlayerSession> findFirstByAccountIdAndEndedAtIsNullOrderByStartedAtDesc(UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PlayerSession> findByAccountIdAndClientSessionId(UUID accountId, UUID clientSessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PlayerSession s WHERE s.accountId = :accountId AND s.endedAt IS NULL")
    List<PlayerSession> lockOpenSessionsForAccount(@Param("accountId") UUID accountId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM PlayerSession s WHERE s.id = :id")
    Optional<PlayerSession> findByIdForUpdate(@Param("id") UUID id);

    @Query(value = """
            SELECT id FROM player_sessions
            WHERE ended_at IS NULL AND client_session_id IS NOT NULL
              AND GREATEST(COALESCE(last_heartbeat_at, started_at), last_activity_at, started_at) < :cutoff
            """, nativeQuery = true)
    List<UUID> findStaleOpenClientSessionIds(@Param("cutoff") Instant cutoff);

    @Modifying
    @Query("UPDATE PlayerSession s SET s.lastActivityAt = :now WHERE s.id = :id AND s.lastActivityAt < :now")
    int touch(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying
    @Query("""
            UPDATE PlayerSession s SET s.lastActivityAt = :now
            WHERE s.accountId = :accountId AND s.endedAt IS NULL AND s.lastActivityAt < :now
            """)
    int touchOpenSession(@Param("accountId") UUID accountId, @Param("now") Instant now);

    @Modifying
    @Query(value = """
            UPDATE player_sessions
            SET ended_at = last_activity_at,
                duration_seconds = CAST(GREATEST(0, FLOOR(
                    EXTRACT(EPOCH FROM (last_activity_at - started_at)))) AS bigint),
                end_reason = :reason
            WHERE id = :id AND ended_at IS NULL
            """, nativeQuery = true)
    int closeSession(@Param("id") UUID id, @Param("reason") String reason);
}
