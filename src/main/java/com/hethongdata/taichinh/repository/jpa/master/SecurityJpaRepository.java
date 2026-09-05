package com.hethongdata.taichinh.repository.jpa.master;

import com.hethongdata.taichinh.entity.master.SecurityEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SecurityJpaRepository extends JpaRepository<SecurityEntity, UUID> {
    Optional<SecurityEntity> findBySymbolIgnoreCase(String symbol);

    List<SecurityEntity> findByIsActiveTrueOrderBySymbolAsc();
}
