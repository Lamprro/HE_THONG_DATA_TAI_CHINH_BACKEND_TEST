package com.hethongdata.taichinh.repository.jpa.ingestion;

import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.dto.ingestion.IngestionRunResponse;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IngestionRunJpaRepository extends JpaRepository<IngestionRunEntity, UUID> {

    Optional<IngestionRunEntity> findTopByIngestionJobIdOrderByStartedAtDesc(UUID ingestionJobId);

    /** Serializes the final validation decision and data-version creation for one ingestion run. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select run from IngestionRunEntity run where run.id = :runId")
    Optional<IngestionRunEntity> findByIdForUpdate(@Param("runId") UUID runId);
    /** List projection deliberately excludes the large request/response bodies and snapshots. */
    @Query("""
        select new com.hethongdata.taichinh.dto.ingestion.IngestionRunResponse(
            run.id, job.id, run.dataSource.id, run.triggerType, cast(run.status as String),
            run.startedAt, run.finishedAt, run.requestQueryParams, run.fetchedCount,
            run.insertedCount, run.updatedCount, run.rejectedCount, run.errorCount,
            run.errorMessage, run.metadata, run.requestMethod, run.requestUrl,
            run.responseHttpStatus, run.responseContentType, run.createdAt)
        from IngestionRunEntity run left join run.ingestionJob job
        order by run.startedAt desc
        """)
    List<IngestionRunResponse> findLatestSummaries(Pageable pageable);
}
