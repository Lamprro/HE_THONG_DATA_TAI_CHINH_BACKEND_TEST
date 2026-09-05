package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.AnalysisReportEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AnalysisReportJpaRepository extends JpaRepository<AnalysisReportEntity, UUID> {}
