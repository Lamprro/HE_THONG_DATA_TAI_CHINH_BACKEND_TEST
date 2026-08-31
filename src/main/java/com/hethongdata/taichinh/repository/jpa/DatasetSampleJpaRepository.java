package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.DatasetSampleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DatasetSampleJpaRepository extends JpaRepository<DatasetSampleEntity, Long> {
}
