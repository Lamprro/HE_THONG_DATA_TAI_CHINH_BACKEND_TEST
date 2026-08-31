package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.ValidationRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ValidationRuleJpaRepository extends JpaRepository<ValidationRuleEntity, Long> {
}
