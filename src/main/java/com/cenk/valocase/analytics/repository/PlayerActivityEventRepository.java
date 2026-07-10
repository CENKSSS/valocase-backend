package com.cenk.valocase.analytics.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.analytics.domain.PlayerActivityEvent;

public interface PlayerActivityEventRepository extends JpaRepository<PlayerActivityEvent, UUID> {
}
