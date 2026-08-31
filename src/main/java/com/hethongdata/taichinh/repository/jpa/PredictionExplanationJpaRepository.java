package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.PredictionExplanationEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PredictionExplanationJpaRepository extends JpaRepository<PredictionExplanationEntity, UUID> {
}
