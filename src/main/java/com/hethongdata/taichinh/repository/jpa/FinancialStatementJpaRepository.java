package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.FinancialStatementEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FinancialStatementJpaRepository extends JpaRepository<FinancialStatementEntity, UUID> {
}
