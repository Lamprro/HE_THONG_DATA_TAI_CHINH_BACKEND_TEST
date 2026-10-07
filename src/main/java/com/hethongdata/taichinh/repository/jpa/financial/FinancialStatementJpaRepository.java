package com.hethongdata.taichinh.repository.jpa.financial;

import com.hethongdata.taichinh.entity.FinancialStatementEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FinancialStatementJpaRepository extends JpaRepository<FinancialStatementEntity, UUID> {
    Optional<FinancialStatementEntity> findByRawPayloadIdAndFinancialPeriodIdAndStatementType(
            UUID rawPayloadId, UUID financialPeriodId, String statementType);

    Optional<FinancialStatementEntity>
            findFirstByCompanyIdAndFinancialPeriodIdAndStatementTypeAndReportScopeAndDataSourceIdAndIsCurrentTrue(
                    UUID companyId,
                    UUID financialPeriodId,
                    String statementType,
                    String reportScope,
                    Long dataSourceId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select statement from FinancialStatementEntity statement where statement.companyId = :companyId "
            + "and statement.financialPeriodId = :periodId and statement.statementType = :statementType "
            + "and statement.reportScope = :reportScope and statement.isCurrent = true")
    List<FinancialStatementEntity> findCurrentBucketForUpdate(
            @Param("companyId") UUID companyId, @Param("periodId") UUID periodId,
            @Param("statementType") String statementType, @Param("reportScope") String reportScope);
}
