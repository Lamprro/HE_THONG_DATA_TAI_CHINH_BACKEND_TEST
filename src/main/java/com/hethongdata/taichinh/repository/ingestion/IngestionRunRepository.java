package com.hethongdata.taichinh.repository.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import com.hethongdata.taichinh.dto.ingestion.IngestionRunResponse;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;

import org.springframework.jdbc.core.JdbcTemplate;
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
    private final JdbcTemplate jdbc;

    public IngestionRunRepository(
            IngestionRunJpaRepository ingestionRuns, ObjectMapper objectMapper, JdbcTemplate jdbc) {
        this.ingestionRuns = ingestionRuns;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
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
                        "provider", request.provider() == null ? "" : request.provider(),
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
    public IngestionRunEntity startInternalBatch(
            DataSourceEntity source, IngestionJobEntity job, String triggerType, String workflow) {
        JsonNode emptyObject = objectMapper.createObjectNode();
        IngestionRunEntity entity =
                IngestionRunEntity.start(
                        source,
                        job,
                        triggerType,
                        objectMapper.valueToTree(Map.of("workflow", workflow)),
                        URI.create("internal://ingestion/" + workflow.toLowerCase()),
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

    /**
     * Latest runs as list rows. Selects only the response columns: request/response headers,
     * snapshots and bodies can be megabytes per run and must not be loaded for a list view.
     */
    public List<IngestionRunResponse> findLatestSummaries(int limit) {
        return jdbc.query("""
                select id, ingestion_job_id, data_source_id, trigger_type, status, started_at, finished_at,
                       request_query_params::text as request_query_params, fetched_count, inserted_count,
                       updated_count, rejected_count, error_count, error_message, metadata::text as metadata,
                       request_method, request_url, response_http_status, response_content_type, created_at
                from ingestion_runs order by started_at desc limit ?""",
                (row, index) -> new IngestionRunResponse(
                        row.getObject("id", UUID.class),
                        row.getObject("ingestion_job_id", UUID.class),
                        row.getLong("data_source_id"),
                        row.getString("trigger_type"),
                        row.getString("status"),
                        instant(row.getTimestamp("started_at")),
                        instant(row.getTimestamp("finished_at")),
                        json(row.getString("request_query_params")),
                        row.getInt("fetched_count"),
                        row.getInt("inserted_count"),
                        row.getInt("updated_count"),
                        row.getInt("rejected_count"),
                        row.getInt("error_count"),
                        row.getString("error_message"),
                        json(row.getString("metadata")),
                        row.getString("request_method"),
                        row.getString("request_url"),
                        (Integer) row.getObject("response_http_status"),
                        row.getString("response_content_type"),
                        instant(row.getTimestamp("created_at"))),
                limit);
    }

    private static Instant instant(java.sql.Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private JsonNode json(String value) {
        try {
            return value == null ? null : objectMapper.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Invalid JSON stored for ingestion run", e);
        }
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
