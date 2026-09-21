package com.hethongdata.taichinh.repository.jpa.financial;

import com.hethongdata.taichinh.entity.FinancialStatementEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.List;
import java.util.UUID;

@Repository
public interface FinancialStatementJpaRepository extends JpaRepository<FinancialStatementEntity, UUID> {
    Optional<FinancialStatementEntity> findByRawPayloadIdAndFinancialPeriodIdAndStatementType(
            UUID rawPayloadId, UUID financialPeriodId, String statementType);
    List<FinancialStatementEntity> findByIsCurrentTrue();
}
