package com.hethongdata.taichinh.entity.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
@Table(name = "ingestion_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IngestionJobEntity {

    @Id
    @UuidGenerator
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "data_source_id", nullable = false)
    private DataSourceEntity dataSource;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "dataset_type", nullable = false)
    private String datasetType;

    @Column(name = "cron_expression")
    private String cronExpression;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private JsonNode parameters;

    @Column(name = "max_retries", nullable = false)
    private short maxRetries;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;

    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static IngestionJobEntity create(
            DataSourceEntity dataSource,
            String code,
            String name,
            String datasetType,
            String cronExpression,
            JsonNode parameters,
            short maxRetries,
            int timeoutSeconds,
            boolean active) {
        IngestionJobEntity entity = new IngestionJobEntity();
        entity.dataSource = dataSource;
        entity.code = code;
        entity.name = name;
        entity.datasetType = datasetType;
        entity.cronExpression = cronExpression;
        entity.parameters = parameters;
        entity.maxRetries = maxRetries;
        entity.timeoutSeconds = timeoutSeconds;
        entity.active = active;
        entity.createdAt = Instant.now();
        entity.updatedAt = entity.createdAt;
        return entity;
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

}


