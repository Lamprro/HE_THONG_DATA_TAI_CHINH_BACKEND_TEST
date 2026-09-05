package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.MacroSeriesEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface MacroSeriesJpaRepository extends JpaRepository<MacroSeriesEntity, UUID> {}
