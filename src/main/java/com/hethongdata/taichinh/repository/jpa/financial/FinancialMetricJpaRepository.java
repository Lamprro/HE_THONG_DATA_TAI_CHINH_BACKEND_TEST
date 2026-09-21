package com.hethongdata.taichinh.repository.jpa.financial;

import com.hethongdata.taichinh.entity.FinancialMetricEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface FinancialMetricJpaRepository extends JpaRepository<FinancialMetricEntity, UUID> {
    @Query("select metric from FinancialMetricEntity metric where metric.companyId = :companyId "
            + "and ((:securityId is null and metric.securityId is null) or metric.securityId = :securityId) "
            + "and metric.metricDefinitionId = :definitionId and metric.asOfDate = :asOfDate "
            + "and metric.dataSourceId = :sourceId and metric.isDerived = false")
    List<FinancialMetricEntity> findProviderIdentity(@Param("companyId") UUID companyId,
            @Param("securityId") UUID securityId, @Param("definitionId") Long definitionId,
            @Param("asOfDate") LocalDate asOfDate, @Param("sourceId") Long sourceId);

    @Query("select metric from FinancialMetricEntity metric where metric.companyId = :companyId "
            + "and ((:securityId is null and metric.securityId is null) or metric.securityId = :securityId) "
            + "and metric.financialPeriodId = :periodId and metric.metricDefinitionId = :definitionId "
            + "and metric.dataSourceId = :sourceId and metric.isDerived = true "
            + "and metric.calculationVersion = :calculationVersion")
    List<FinancialMetricEntity> findDerivedIdentity(@Param("companyId") UUID companyId,
            @Param("securityId") UUID securityId, @Param("periodId") UUID periodId,
            @Param("definitionId") Long definitionId, @Param("sourceId") Long sourceId,
            @Param("calculationVersion") String calculationVersion);
}
