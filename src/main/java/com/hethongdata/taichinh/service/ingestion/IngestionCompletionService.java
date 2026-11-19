package com.hethongdata.taichinh.service.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity;
import com.hethongdata.taichinh.repository.ingestion.IngestionRunRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Persists a successful fetch (raw payload + run success) in one transaction, separate from the
 * non-transactional external call in {@link IngestionService}.
 */
@Service
public class IngestionCompletionService {

    private final RawPayloadJpaRepository rawPayloads;
    private final IngestionRunRepository ingestionRunRepository;

    public IngestionCompletionService(
            RawPayloadJpaRepository rawPayloads,
            IngestionRunRepository ingestionRunRepository) {
        this.rawPayloads = rawPayloads;
        this.ingestionRunRepository = ingestionRunRepository;
    }

    @Transactional
    public UUID persistSuccess(
            IngestionRunEntity run,
            DataSourceEntity source,
            ExternalFetchRequest request,
            ExternalFetchResponse response,
            JsonNode jsonBody,
            String textBody,
            String checksum,
            boolean duplicate,
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
                        jsonBody,
                        textBody,
                        checksum,
                        response.fetchedAt(),
                        securityId);
        UUID rawPayloadId = rawPayloads.save(entity).getId();
        ingestionRunRepository.markSuccess(run, response, jsonBody, textBody, duplicate);
        return rawPayloadId;
    }
}
