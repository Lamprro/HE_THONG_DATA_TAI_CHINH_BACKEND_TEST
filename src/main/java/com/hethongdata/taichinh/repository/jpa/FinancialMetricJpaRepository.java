package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.FinancialMetricEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface FinancialMetricJpaRepository extends JpaRepository<FinancialMetricEntity, UUID> {}
