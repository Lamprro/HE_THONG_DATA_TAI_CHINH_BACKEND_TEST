package com.hethongdata.taichinh.repository.jpa.financial;

import com.hethongdata.taichinh.entity.FinancialStatementItemEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;
import java.util.Collection;
import java.util.List;

@Repository
public interface FinancialStatementItemJpaRepository
        extends JpaRepository<FinancialStatementItemEntity, UUID> {
    List<FinancialStatementItemEntity> findByFinancialStatementIdIn(Collection<UUID> statementIds);
}
