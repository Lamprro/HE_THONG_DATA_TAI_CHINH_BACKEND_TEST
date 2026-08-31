package com.hethongdata.taichinh.repository.jpa.master;

import com.hethongdata.taichinh.entity.master.CompanyAliasEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CompanyAliasJpaRepository extends JpaRepository<CompanyAliasEntity, Long> {
    java.util.List<CompanyAliasEntity> findByCompanyIdOrderByAliasAsc(java.util.UUID companyId);
    boolean existsByCompanyIdAndAliasIgnoreCase(java.util.UUID companyId, String alias);
}
