package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.MacroObservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MacroObservationJpaRepository extends JpaRepository<MacroObservationEntity, Long> {
}
