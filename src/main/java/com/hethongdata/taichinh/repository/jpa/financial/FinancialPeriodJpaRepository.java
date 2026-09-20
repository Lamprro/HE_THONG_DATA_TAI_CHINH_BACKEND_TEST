package com.hethongdata.taichinh.repository.jpa.financial;

import com.hethongdata.taichinh.entity.FinancialPeriodEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FinancialPeriodJpaRepository extends JpaRepository<FinancialPeriodEntity, UUID> {
    Optional<FinancialPeriodEntity> findByFiscalYearAndPeriodTypeAndEndDate(
            Short fiscalYear, String periodType, LocalDate endDate);
}
