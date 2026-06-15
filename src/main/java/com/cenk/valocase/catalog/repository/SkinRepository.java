package com.cenk.valocase.catalog.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.cenk.valocase.catalog.domain.Skin;

public interface SkinRepository extends JpaRepository<Skin, String> {

    List<Skin> findByActiveTrueOrderByDisplayNameAsc();
}
