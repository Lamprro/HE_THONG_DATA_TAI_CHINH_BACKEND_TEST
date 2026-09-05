package com.hethongdata.taichinh.repository.jpa.validation;

import com.hethongdata.taichinh.entity.validation.ValidationRuleEntity;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ValidationRuleJpaRepository extends JpaRepository<ValidationRuleEntity, Long> {
    Optional<ValidationRuleEntity> findByCode(String code);
    List<ValidationRuleEntity> findByIsActiveTrueOrderByIdAsc();
}
