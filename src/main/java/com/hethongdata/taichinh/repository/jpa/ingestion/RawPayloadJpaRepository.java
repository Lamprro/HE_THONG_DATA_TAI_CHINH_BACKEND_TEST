package com.hethongdata.taichinh.repository.jpa.ingestion;

import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RawPayloadJpaRepository extends JpaRepository<RawPayloadEntity, UUID> {
    Optional<RawPayloadEntity> findTopByDataSourceIdAndChecksumSha256OrderByFetchedAtDesc(
            Long dataSourceId, String checksumSha256);
    List<RawPayloadEntity> findByIngestionRunIdOrderByFetchedAtDesc(UUID ingestionRunId);
    boolean existsByDataSourceIdAndChecksumSha256AndIdNot(Long dataSourceId, String checksumSha256, UUID id);
    @Query("select raw from RawPayloadEntity raw where not exists (select result from ValidationResultEntity result where result.rawPayloadId = raw.id) order by raw.fetchedAt asc")
    List<RawPayloadEntity> findUnvalidated(Pageable pageable);
}


