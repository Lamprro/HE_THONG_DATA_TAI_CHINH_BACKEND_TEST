package com.hethongdata.taichinh.repository.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class RawPayloadRepository {

    private final RawPayloadJpaRepository rawPayloads;

    public RawPayloadRepository(RawPayloadJpaRepository rawPayloads) {
        this.rawPayloads = rawPayloads;
    }

    public Optional<UUID> findLatestByChecksum(long dataSourceId, String checksum) {
        return rawPayloads
                .findTopByDataSourceIdAndChecksumSha256OrderByFetchedAtDesc(dataSourceId, checksum)
                .map(RawPayloadEntity::getId);
    }

    @Transactional
    public UUID save(
            IngestionRunEntity run,
            DataSourceEntity source,
            ExternalFetchRequest request,
            ExternalFetchResponse response,
            JsonNode payload,
            String rawText,
            String checksum,
            UUID securityId) {
        String externalKey =
                request.provider()
                        + ":"
                        + request.operation().name().toLowerCase()
                        + ":"
                        + (request.symbol() == null ? "system" : request.symbol());
        RawPayloadEntity entity =
                RawPayloadEntity.create(
                        run,
                        source,
                        externalKey,
                        request.operation().name(),
                        request.symbol(),
                        response.sourceUri().toString(),
                        response.contentType(),
                        payload,
                        rawText,
                        checksum,
                        response.fetchedAt(),
                        securityId);
        return rawPayloads.save(entity).getId();
    }

    public Optional<RawPayloadEntity> findById(UUID id) {
        return rawPayloads.findById(id);
    }

    public List<RawPayloadEntity> findByRunId(UUID runId) {
        return rawPayloads.findByIngestionRunIdOrderByFetchedAtDesc(runId);
    }
}
