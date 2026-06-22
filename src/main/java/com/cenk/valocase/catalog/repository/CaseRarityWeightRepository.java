package com.cenk.valocase.catalog.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cenk.valocase.catalog.domain.CaseRarityWeight;

public interface CaseRarityWeightRepository extends JpaRepository<CaseRarityWeight, UUID> {

    List<CaseRarityWeight> findByCaseId(String caseId);

    @Modifying
    @Query("delete from CaseRarityWeight w where w.caseId = :caseId")
    int deleteByCaseId(@Param("caseId") String caseId);
}
