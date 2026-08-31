package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.FinancialPeriodEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface FinancialPeriodJpaRepository extends JpaRepository<FinancialPeriodEntity, UUID> {
}
