package com.cenk.valocase.mission.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.mission.domain.MissionDefinition;

public interface MissionDefinitionRepository extends JpaRepository<MissionDefinition, UUID> {

    List<MissionDefinition> findByActiveTrueOrderBySortOrderAsc();

    List<MissionDefinition> findByActiveTrueAndEventType(String eventType);
}
