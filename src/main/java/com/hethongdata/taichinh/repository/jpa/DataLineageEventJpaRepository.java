package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.DataLineageEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DataLineageEventJpaRepository extends JpaRepository<DataLineageEventEntity, Long> {
}
