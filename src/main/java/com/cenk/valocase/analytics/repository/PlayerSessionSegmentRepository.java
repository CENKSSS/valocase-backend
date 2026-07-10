package com.cenk.valocase.analytics.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cenk.valocase.analytics.domain.PlayerSessionSegment;

import jakarta.persistence.LockModeType;

public interface PlayerSessionSegmentRepository extends JpaRepository<PlayerSessionSegment, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<PlayerSessionSegment> findFirstBySessionIdAndEndedAtIsNull(UUID sessionId);

    boolean existsBySessionIdAndEndedAtIsNull(UUID sessionId);

    @Modifying
    @Query("""
            UPDATE PlayerSessionSegment g
            SET g.endedAt = :endedAt, g.endReason = :reason, g.estimated = :estimated
            WHERE g.sessionId = :sessionId AND g.endedAt IS NULL
            """)
    int closeOpenSegment(@Param("sessionId") UUID sessionId,
                         @Param("endedAt") Instant endedAt,
                         @Param("reason") String reason,
                         @Param("estimated") boolean estimated);
}
