package com.hethongdata.taichinh.repository.jpa.ingestion;

import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RawPayloadJpaRepository extends JpaRepository<RawPayloadEntity, UUID> {
    Optional<RawPayloadEntity> findTopByDataSourceIdAndChecksumSha256OrderByFetchedAtDesc(
            Long dataSourceId, String checksumSha256);

    List<RawPayloadEntity> findByIngestionRunIdOrderByFetchedAtDesc(UUID ingestionRunId);

    List<RawPayloadEntity> findByIngestionRunIdAndEntityTypeOrderByFetchedAtAsc(
            UUID ingestionRunId, String entityType);

    List<RawPayloadEntity> findByIngestionRunIdAndEntityTypeInOrderByFetchedAtAsc(
            UUID ingestionRunId, List<String> entityTypes);

    long countByIngestionRunId(UUID ingestionRunId);

    boolean existsByIngestionRunIdAndEntityType(UUID ingestionRunId, String entityType);

    @Query(value = """
            SELECT * FROM raw_payloads
            WHERE entity_type = 'NEWS_DATA'
              AND payload->>'requested_url' = :url
              AND payload->>'extraction_status' = 'SUCCESS'
              AND raw_text IS NOT NULL
            ORDER BY fetched_at DESC
            LIMIT 1
            """, nativeQuery = true)
    Optional<RawPayloadEntity> findReusableNewsDataByRequestedUrl(@Param("url") String url);

    @Query(value = """
            SELECT EXISTS(SELECT 1 FROM raw_payloads
              WHERE entity_type = 'NEWS_DATA'
                AND payload->>'requested_url' = :url
                AND payload->>'extraction_status' = 'SUCCESS'
                AND ((:securityId IS NOT NULL AND security_id = :securityId)
                  OR (:securityId IS NULL AND upper(source_symbol) = upper(:sourceSymbol)))
            )
            """, nativeQuery = true)
    boolean existsNewsDataForSourceAndUrl(
            @Param("url") String url,
            @Param("securityId") UUID securityId,
            @Param("sourceSymbol") String sourceSymbol);

    @Query(value = """
            SELECT EXISTS(SELECT 1 FROM raw_payloads
              WHERE entity_type = 'NEWS_DATA' AND payload->>'requested_url' = :url)
            """, nativeQuery = true)
    boolean existsNewsDataForRequestedUrl(@Param("url") String url);

    @Query(value = """
            SELECT EXISTS(SELECT 1 FROM raw_payloads
              WHERE entity_type = 'NEWS_DATA' AND payload->>'requested_url' = :url AND id <> :rawId)
            """, nativeQuery = true)
    boolean existsOtherNewsDataForRequestedUrl(@Param("url") String url, @Param("rawId") UUID rawId);

    boolean existsByDataSourceIdAndChecksumSha256AndIdNot(
            Long dataSourceId, String checksumSha256, UUID id);

    @Query(
            "select raw from RawPayloadEntity raw where not exists (select result from ValidationResultEntity result where result.rawPayloadId = raw.id) order by raw.fetchedAt asc")
    List<RawPayloadEntity> findUnvalidated(Pageable pageable);

    @Query("select raw from RawPayloadEntity raw where upper(raw.sourceSymbol) in :symbols "
            + "and not exists (select result from ValidationResultEntity result where result.rawPayloadId = raw.id) "
            + "order by raw.fetchedAt asc")
    List<RawPayloadEntity> findUnvalidatedForSymbols(
            @Param("symbols") List<String> symbols, Pageable pageable);
}
