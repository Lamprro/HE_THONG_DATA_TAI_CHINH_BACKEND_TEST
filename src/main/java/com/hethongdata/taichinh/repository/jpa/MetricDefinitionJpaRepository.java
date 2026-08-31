package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.MetricDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MetricDefinitionJpaRepository extends JpaRepository<MetricDefinitionEntity, Long> {
}
