package com.hethongdata.taichinh.repository.jpa.master;

import com.hethongdata.taichinh.entity.master.SecurityEntity;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SecurityJpaRepository extends JpaRepository<SecurityEntity, UUID> {
    Optional<SecurityEntity> findBySymbolIgnoreCase(String symbol);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select security from SecurityEntity security where security.id = :id")
    Optional<SecurityEntity> findByIdForUpdate(@Param("id") UUID id);

    List<SecurityEntity> findByIsActiveTrueOrderBySymbolAsc();
}
