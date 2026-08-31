package com.hethongdata.taichinh.repository.jpa.ingestion;

import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DataSourceJpaRepository extends JpaRepository<DataSourceEntity, Long> {

    Optional<DataSourceEntity> findByCodeIgnoreCase(String code);

    @Query("""
            SELECT source FROM DataSourceEntity source
            WHERE source.active = true
              AND (LOWER(source.code) = LOWER(:provider) OR LOWER(source.provider) = LOWER(:provider))
            ORDER BY CASE WHEN LOWER(source.code) = LOWER(:provider) THEN 0 ELSE 1 END, source.priority, source.id
            """)
    Optional<DataSourceEntity> findActiveByProvider(@Param("provider") String provider);
}


