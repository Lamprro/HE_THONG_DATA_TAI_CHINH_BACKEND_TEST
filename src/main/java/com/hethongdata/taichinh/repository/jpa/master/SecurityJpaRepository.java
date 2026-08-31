package com.hethongdata.taichinh.repository.jpa.master;

import com.hethongdata.taichinh.entity.master.SecurityEntity;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SecurityJpaRepository extends JpaRepository<SecurityEntity, UUID> {
    java.util.Optional<SecurityEntity> findBySymbolIgnoreCase(String symbol);
    java.util.List<SecurityEntity> findByIsActiveTrueOrderBySymbolAsc();
}
