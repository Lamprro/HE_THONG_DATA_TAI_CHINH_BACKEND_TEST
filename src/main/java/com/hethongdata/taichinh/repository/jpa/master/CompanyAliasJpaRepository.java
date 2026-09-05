package com.hethongdata.taichinh.repository.jpa.master;

import com.hethongdata.taichinh.entity.master.CompanyAliasEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CompanyAliasJpaRepository extends JpaRepository<CompanyAliasEntity, Long> {
    List<CompanyAliasEntity> findByCompanyIdOrderByAliasAsc(UUID companyId);

    boolean existsByCompanyIdAndAliasIgnoreCase(UUID companyId, String alias);
}
