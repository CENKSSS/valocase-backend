package com.cenk.valocase.mission.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.cenk.valocase.mission.domain.PlayerMission;

public interface PlayerMissionRepository extends JpaRepository<PlayerMission, UUID> {

    List<PlayerMission> findByAccountId(UUID accountId);

    Optional<PlayerMission> findByAccountIdAndMissionIdAndPeriodKey(UUID accountId, UUID missionId, String periodKey);

    /** Locked load used when claiming, to serialize concurrent claim attempts. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select pm from PlayerMission pm where pm.accountId = :accountId "
            + "and pm.missionId = :missionId and pm.periodKey = :periodKey")
    Optional<PlayerMission> findForUpdate(@Param("accountId") UUID accountId,
                                          @Param("missionId") UUID missionId,
                                          @Param("periodKey") String periodKey);
}
