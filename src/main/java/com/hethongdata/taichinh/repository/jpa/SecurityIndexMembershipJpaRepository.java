package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.SecurityIndexMembershipEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface SecurityIndexMembershipJpaRepository
        extends JpaRepository<SecurityIndexMembershipEntity, UUID> {}
