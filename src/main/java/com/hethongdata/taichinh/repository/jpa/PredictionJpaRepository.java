package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.PredictionEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PredictionJpaRepository extends JpaRepository<PredictionEntity, UUID> {}
