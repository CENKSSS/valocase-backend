package com.cenk.valocase.daily.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.daily.domain.DailyClaim;

public interface DailyClaimRepository extends JpaRepository<DailyClaim, UUID> {
}
