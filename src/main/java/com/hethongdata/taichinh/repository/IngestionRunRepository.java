package com.hethongdata.taichinh.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import com.hethongdata.taichinh.domain.ingestion.IngestionRun;
import com.hethongdata.taichinh.domain.ingestion.IngestionStatus;
import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class IngestionRunRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public IngestionRunRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public UUID start(long dataSourceId, ExternalFetchRequest request, URI requestUri) {
        Map<String, Object> query = Map.of(
                "operation", request.operation().name(),
                "provider", request.provider(),
                "symbol", request.symbol() == null ? "" : request.symbol(),
                "startDate", request.startDate() == null ? "" : request.startDate().toString(),
                "endDate", request.endDate() == null ? "" : request.endDate().toString(),
                "interval", request.interval() == null ? "" : request.interval(),
                "parameters", request.parameters());

        return jdbcClient.sql("""
                        INSERT INTO ingestion_runs (
                            data_source_id, trigger_type, status, started_at,
                            request_query_params, request_method, request_url,
                            request_headers, response_headers, metadata
                        ) VALUES (
                            :dataSourceId, 'MANUAL', 'RUNNING', NOW(),
                            CAST(:query AS jsonb), 'GET', :requestUrl,
                            '{}'::jsonb, '{}'::jsonb, CAST(:metadata AS jsonb)
                        )
                        RETURNING id
                        """)
                .param("dataSourceId", dataSourceId)
                .param("query", JsonDatabaseSupport.write(objectMapper, query))
                .param("requestUrl", requestUri.toString())
                .param("metadata", JsonDatabaseSupport.write(objectMapper, Map.of("phase", 1)))
                .query(UUID.class)
                .single();
    }

    public void markSuccess(
            UUID runId,
            ExternalFetchResponse response,
            JsonNode responseJson,
            String responseText,
            boolean duplicate) {
        jdbcClient.sql("""
                        UPDATE ingestion_runs
                        SET status = 'SUCCESS', finished_at = NOW(),
                            fetched_count = 1, inserted_count = 1,
                            response_http_status = :status,
                            response_content_type = :contentType,
                            response_headers = CAST(:headers AS jsonb),
                            response_snapshot = CAST(:responseJson AS jsonb),
                            response_text = :responseText,
                            metadata = metadata || CAST(:metadata AS jsonb)
                        WHERE id = :runId
                        """)
                .param("status", response.httpStatus())
                .param("contentType", response.contentType())
                .param("headers", JsonDatabaseSupport.write(objectMapper, response.responseHeaders()))
                .param("responseJson", responseJson == null ? null : responseJson.toString())
                .param("responseText", responseText)
                .param("metadata", JsonDatabaseSupport.write(objectMapper, Map.of("duplicateChecksum", duplicate)))
                .param("runId", runId)
                .update();
    }

    public void markFailed(UUID runId, String category, Integer upstreamStatus, String message) {
        jdbcClient.sql("""
                        UPDATE ingestion_runs
                        SET status = 'FAILED', finished_at = NOW(), error_count = 1,
                            error_message = :message,
                            response_http_status = :upstreamStatus,
                            metadata = metadata || CAST(:metadata AS jsonb)
                        WHERE id = :runId
                        """)
                .param("message", abbreviate(message, 4000))
                .param("upstreamStatus", upstreamStatus)
                .param("metadata", JsonDatabaseSupport.write(objectMapper, Map.of("errorCategory", category)))
                .param("runId", runId)
                .update();
    }

    public Optional<IngestionRun> findById(UUID id) {
        return jdbcClient.sql(BASE_SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    public List<IngestionRun> findLatest(int limit) {
        return jdbcClient.sql(BASE_SELECT + " ORDER BY started_at DESC LIMIT :limit")
                .param("limit", limit)
                .query(this::mapRow)
                .list();
    }

    private IngestionRun mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp finishedAt = rs.getTimestamp("finished_at");
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new IngestionRun(
                rs.getObject("id", UUID.class),
                rs.getLong("data_source_id"),
                rs.getString("trigger_type"),
                IngestionStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("started_at").toInstant(),
                finishedAt == null ? null : finishedAt.toInstant(),
                JsonDatabaseSupport.read(objectMapper, rs.getString("request_query_params")),
                rs.getInt("fetched_count"),
                rs.getInt("inserted_count"),
                rs.getInt("updated_count"),
                rs.getInt("rejected_count"),
                rs.getInt("error_count"),
                rs.getString("error_message"),
                JsonDatabaseSupport.read(objectMapper, rs.getString("metadata")),
                rs.getString("request_method"),
                rs.getString("request_url"),
                (Integer) rs.getObject("response_http_status"),
                rs.getString("response_content_type"),
                createdAt == null ? Instant.EPOCH : createdAt.toInstant());
    }

    private static String abbreviate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private static final String BASE_SELECT = """
            SELECT id, data_source_id, trigger_type, status, started_at, finished_at,
                   request_query_params, fetched_count, inserted_count, updated_count,
                   rejected_count, error_count, error_message, metadata, request_method,
                   request_url, response_http_status, response_content_type, created_at
            FROM ingestion_runs
            """;
}
