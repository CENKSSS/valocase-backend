package com.cenk.valocase.daily.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import com.cenk.valocase.daily.domain.DailyRewardState;

public interface DailyRewardStateRepository extends JpaRepository<DailyRewardState, UUID> {

    /** Loads the account's streak state with a write lock to serialize claims. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from DailyRewardState s where s.accountId = :accountId")
    Optional<DailyRewardState> findForUpdate(@Param("accountId") UUID accountId);
}
