package com.cenk.valocase.catalog.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.catalog.domain.CaseDefinition;

public interface CaseDefinitionRepository extends JpaRepository<CaseDefinition, String> {

    List<CaseDefinition> findByActiveTrueOrderByDisplayNameAsc();
}
