package com.cenk.valocase.catalog.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.cenk.valocase.catalog.domain.CaseEntry;

public interface CaseEntryRepository extends JpaRepository<CaseEntry, UUID> {

    List<CaseEntry> findByCaseIdOrderBySkinIdAsc(String caseId);

    /**
     * Bulk-deletes all drop entries for a case. Implemented as an explicit
     * modifying query so the DELETE executes immediately (before any subsequent
     * re-insert of the case's pool), avoiding unique-constraint conflicts.
     */
    @Modifying
    @Query("delete from CaseEntry e where e.caseId = :caseId")
    int deleteEntriesByCaseId(@Param("caseId") String caseId);
}
