package com.hethongdata.taichinh.repository.jpa.master;

import com.hethongdata.taichinh.entity.master.CompanyEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface CompanyJpaRepository extends JpaRepository<CompanyEntity, UUID> {
    Optional<CompanyEntity> findByCompanyCodeIgnoreCase(String companyCode);
}
