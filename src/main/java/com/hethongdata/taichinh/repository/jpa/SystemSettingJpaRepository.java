package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.SystemSettingEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SystemSettingJpaRepository extends JpaRepository<SystemSettingEntity, String> {}
