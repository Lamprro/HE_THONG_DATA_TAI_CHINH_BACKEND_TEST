package com.hethongdata.taichinh.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "llm_runs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LlmRunEntity {

    @Id
    @UuidGenerator
    @Column(name = "id")
    private UUID id;

    @Column(name = "operation_type")
    private String operationType;

    @Column(name = "provider")
    private String provider;

    @Column(name = "model_name")
    private String modelName;

    @Column(name = "prompt_version")
    private String promptVersion;

    @Column(name = "status")
    private String status;

    @Column(name = "input_hash")
    private String inputHash;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "request_metadata")
    private JsonNode requestMetadata;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response_metadata")
    private JsonNode responseMetadata;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "latency_ms")
    private Integer latencyMs;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

}
