package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.CompanyAliasEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface CompanyAliasJpaRepository extends JpaRepository<CompanyAliasEntity, Long> {
}
