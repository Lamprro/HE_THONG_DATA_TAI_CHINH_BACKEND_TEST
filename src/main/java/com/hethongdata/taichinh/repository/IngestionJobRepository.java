package com.hethongdata.taichinh.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.entity.ingestion.DataSourceEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.DataSourceJpaRepository;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionJobJpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class IngestionJobRepository {

    private final IngestionJobJpaRepository ingestionJobs;
    private final DataSourceJpaRepository dataSources;

    public IngestionJobRepository(
            IngestionJobJpaRepository ingestionJobs,
            DataSourceJpaRepository dataSources) {
        this.ingestionJobs = ingestionJobs;
        this.dataSources = dataSources;
    }

    @Transactional
    public IngestionJobEntity create(
            String dataSourceCode,
            String code,
            String name,
            String datasetType,
            String cronExpression,
            JsonNode parameters,
            short maxRetries,
            int timeoutSeconds,
            boolean active) {
        DataSourceEntity source = dataSources.findByCodeIgnoreCase(dataSourceCode)
                .filter(DataSourceEntity::isActive)
                .orElseThrow(() -> new IllegalArgumentException("No active data source with code " + dataSourceCode));
        IngestionJobEntity entity = IngestionJobEntity.create(
                source, code, name, datasetType, cronExpression, parameters,
                maxRetries, timeoutSeconds, active);
        return ingestionJobs.save(entity);
    }

    /** Idempotent write used by the versioned job catalog during controlled seeding. */
    @Transactional
    public IngestionJobEntity upsert(
            String dataSourceCode,
            String code,
            String name,
            String datasetType,
            String cronExpression,
            JsonNode parameters,
            short maxRetries,
            int timeoutSeconds,
            boolean active) {
        DataSourceEntity source = dataSources.findByCodeIgnoreCase(dataSourceCode)
                .filter(DataSourceEntity::isActive)
                .orElseThrow(() -> new IllegalArgumentException("No active data source with code " + dataSourceCode));
        IngestionJobEntity entity = ingestionJobs.findByCodeIgnoreCase(code)
                .orElseGet(() -> IngestionJobEntity.create(
                        source, code, name, datasetType, cronExpression, parameters,
                        maxRetries, timeoutSeconds, active));
        entity.refreshDefinition(source, name, datasetType, cronExpression, parameters,
                maxRetries, timeoutSeconds, active);
        return ingestionJobs.save(entity);
    }

    @Transactional(readOnly = true)
    public Optional<IngestionJobEntity> findById(UUID id) {
        return ingestionJobs.findById(id);
    }

    public List<IngestionJobEntity> findActiveEntities() {
        return ingestionJobs.findByActiveTrue();
    }

    public Optional<IngestionJobEntity> findEntityById(UUID id) {
        return ingestionJobs.findById(id);
    }

    @Transactional
    public IngestionJobEntity setActive(UUID id, boolean active) {
        IngestionJobEntity entity = ingestionJobs.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Ingestion job not found: " + id));
        entity.setActive(active);
        return ingestionJobs.save(entity);
    }

    /** Keeps historical runs immutable while preventing retired definitions from being scheduled again. */
    @Transactional
    public int deactivateByCodes(List<String> codes) {
        int changed = 0;
        for (String code : codes) {
            Optional<IngestionJobEntity> job = ingestionJobs.findByCodeIgnoreCase(code);
            if (job.isPresent() && job.get().isActive()) {
                job.get().setActive(false);
                ingestionJobs.save(job.get());
                changed++;
            }
        }
        return changed;
    }

}
