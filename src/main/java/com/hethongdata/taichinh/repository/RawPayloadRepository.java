package com.hethongdata.taichinh.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.application.port.model.ExternalFetchRequest;
import com.hethongdata.taichinh.application.port.model.ExternalFetchResponse;
import com.hethongdata.taichinh.domain.ingestion.RawPayload;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class RawPayloadRepository {

    private final JdbcClient jdbcClient;
    private final ObjectMapper objectMapper;

    public RawPayloadRepository(JdbcClient jdbcClient, ObjectMapper objectMapper) {
        this.jdbcClient = jdbcClient;
        this.objectMapper = objectMapper;
    }

    public Optional<UUID> findLatestByChecksum(long dataSourceId, String checksum) {
        return jdbcClient.sql("""
                        SELECT id FROM raw_payloads
                        WHERE data_source_id = :dataSourceId AND checksum_sha256 = :checksum
                        ORDER BY fetched_at DESC LIMIT 1
                        """)
                .param("dataSourceId", dataSourceId)
                .param("checksum", checksum)
                .query(UUID.class)
                .optional();
    }

    public UUID save(
            UUID runId,
            long dataSourceId,
            ExternalFetchRequest request,
            ExternalFetchResponse response,
            JsonNode payload,
            String rawText,
            String checksum) {
        String externalKey = request.provider() + ":" + request.operation().name().toLowerCase()
                + ":" + (request.symbol() == null ? "system" : request.symbol());
        return jdbcClient.sql("""
                        INSERT INTO raw_payloads (
                            ingestion_run_id, data_source_id, external_key, entity_type,
                            source_symbol, source_url, content_type, payload, raw_text,
                            checksum_sha256, fetched_at
                        ) VALUES (
                            :runId, :dataSourceId, :externalKey, :entityType,
                            :sourceSymbol, :sourceUrl, :contentType, CAST(:payload AS jsonb), :rawText,
                            :checksum, :fetchedAt
                        )
                        RETURNING id
                        """)
                .param("runId", runId)
                .param("dataSourceId", dataSourceId)
                .param("externalKey", externalKey)
                .param("entityType", request.operation().name())
                .param("sourceSymbol", request.symbol())
                .param("sourceUrl", response.sourceUri().toString())
                .param("contentType", response.contentType())
                .param("payload", payload == null ? null : payload.toString())
                .param("rawText", rawText)
                .param("checksum", checksum)
                .param("fetchedAt", Timestamp.from(response.fetchedAt()))
                .query(UUID.class)
                .single();
    }

    public Optional<RawPayload> findById(UUID id) {
        return jdbcClient.sql(BASE_SELECT + " WHERE id = :id")
                .param("id", id)
                .query(this::mapRow)
                .optional();
    }

    public List<RawPayload> findByRunId(UUID runId) {
        return jdbcClient.sql(BASE_SELECT + " WHERE ingestion_run_id = :runId ORDER BY fetched_at DESC")
                .param("runId", runId)
                .query(this::mapRow)
                .list();
    }

    private RawPayload mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new RawPayload(
                rs.getObject("id", UUID.class),
                rs.getObject("ingestion_run_id", UUID.class),
                rs.getLong("data_source_id"),
                rs.getString("external_key"),
                rs.getString("entity_type"),
                rs.getString("source_symbol"),
                rs.getString("source_url"),
                rs.getString("content_type"),
                JsonDatabaseSupport.read(objectMapper, rs.getString("payload")),
                rs.getString("raw_text"),
                rs.getString("checksum_sha256"),
                rs.getTimestamp("fetched_at").toInstant(),
                createdAt == null ? null : createdAt.toInstant());
    }

    private static final String BASE_SELECT = """
            SELECT id, ingestion_run_id, data_source_id, external_key, entity_type,
                   source_symbol, source_url, content_type, payload, raw_text,
                   checksum_sha256, fetched_at, created_at
            FROM raw_payloads
            """;
}
