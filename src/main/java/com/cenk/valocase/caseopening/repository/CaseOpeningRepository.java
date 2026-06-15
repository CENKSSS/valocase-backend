package com.cenk.valocase.caseopening.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.caseopening.domain.CaseOpening;

public interface CaseOpeningRepository extends JpaRepository<CaseOpening, UUID> {

    List<CaseOpening> findByAccountIdOrderByCreatedAtDesc(UUID accountId);
}
