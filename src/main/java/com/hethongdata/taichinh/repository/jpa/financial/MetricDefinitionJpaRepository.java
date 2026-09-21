package com.hethongdata.taichinh.repository.jpa.financial;

import com.hethongdata.taichinh.entity.MetricDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface MetricDefinitionJpaRepository extends JpaRepository<MetricDefinitionEntity, Long> {
    Optional<MetricDefinitionEntity> findByCode(String code);
    List<MetricDefinitionEntity> findByCodeIn(Collection<String> codes);
}
