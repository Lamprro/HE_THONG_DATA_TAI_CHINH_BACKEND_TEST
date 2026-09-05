package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.AuditLogEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogJpaRepository extends JpaRepository<AuditLogEntity, Long> {}
