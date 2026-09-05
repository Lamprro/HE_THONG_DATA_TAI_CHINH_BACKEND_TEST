package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.FinancialPeriodEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface FinancialPeriodJpaRepository extends JpaRepository<FinancialPeriodEntity, UUID> {}
