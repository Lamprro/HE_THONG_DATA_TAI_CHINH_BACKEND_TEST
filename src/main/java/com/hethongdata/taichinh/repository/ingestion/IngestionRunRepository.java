package com.hethongdata.taichinh.repository.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class IngestionRunRepository {

    private final IngestionRunJpaRepository ingestionRuns;
    private final ObjectMapper objectMapper;

    public IngestionRunRepository(
            IngestionRunJpaRepository ingestionRuns, ObjectMapper objectMapper) {
        this.ingestionRuns = ingestionRuns;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public IngestionRunEntity start(
            DataSourceEntity source,
            IngestionJobEntity job,
            String triggerType,
            ExternalFetchRequest request,
            URI requestUri) {
        Map<String, Object> query =
                Map.of(
                        "operation", request.operation().name(),
                        "provider", request.provider(),
                        "symbol", request.symbol() == null ? "" : request.symbol(),
                        "startDate",
                                request.startDate() == null ? "" : request.startDate().toString(),
                        "endDate", request.endDate() == null ? "" : request.endDate().toString(),
                        "interval", request.interval() == null ? "" : request.interval(),
                        "parameters", request.parameters());
        JsonNode emptyObject = objectMapper.createObjectNode();
        IngestionRunEntity entity =
                IngestionRunEntity.start(
                        source,
                        job,
                        triggerType,
                        objectMapper.valueToTree(query),
                        requestUri,
                        emptyObject,
                        Instant.now());
        return ingestionRuns.save(entity);
    }

    @Transactional
    public void markSuccess(
            IngestionRunEntity run,
            ExternalFetchResponse response,
            JsonNode responseJson,
            String responseText,
            boolean duplicate) {
        run.markSuccess(
                response.httpStatus(),
                response.contentType(),
                objectMapper.valueToTree(response.responseHeaders()),
                responseJson,
                responseText,
                objectMapper.valueToTree(Map.of("phase", 1, "duplicateChecksum", duplicate)),
                Instant.now());
        ingestionRuns.save(run);
    }

    @Transactional
    public void markFailed(
            IngestionRunEntity run, String category, Integer upstreamStatus, String message) {
        run.markFailed(
                upstreamStatus == null ? 0 : upstreamStatus,
                null,
                objectMapper.createObjectNode(),
                null,
                null,
                abbreviate(message, 4000),
                objectMapper.valueToTree(Map.of("phase", 1, "errorCategory", category)),
                Instant.now());
        ingestionRuns.save(run);
    }

    @Transactional
    public void markFailedResponse(
            IngestionRunEntity run,
            String category,
            ExternalFetchResponse response,
            JsonNode responseJson,
            String responseText,
            String message) {
        run.markFailed(
                response.httpStatus(),
                response.contentType(),
                objectMapper.valueToTree(response.responseHeaders()),
                responseJson,
                responseText,
                abbreviate(message, 4000),
                objectMapper.valueToTree(Map.of("phase", 1, "errorCategory", category)),
                Instant.now());
        ingestionRuns.save(run);
    }

    public Optional<IngestionRunEntity> findById(UUID id) {
        return ingestionRuns.findById(id);
    }

    public List<IngestionRunEntity> findLatest(int limit) {
        return ingestionRuns.findAllByOrderByStartedAtDesc(PageRequest.of(0, limit));
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
