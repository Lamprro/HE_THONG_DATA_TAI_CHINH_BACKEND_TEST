package com.hethongdata.taichinh.repository.jpa;

import com.hethongdata.taichinh.entity.DataVersionEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DataVersionJpaRepository extends JpaRepository<DataVersionEntity, UUID> {
}
