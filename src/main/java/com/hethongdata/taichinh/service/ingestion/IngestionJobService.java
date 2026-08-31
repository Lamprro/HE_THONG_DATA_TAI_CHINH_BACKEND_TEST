package com.hethongdata.taichinh.service.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.dto.ingestion.CreateIngestionJobRequest;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.dto.ingestion.IngestionJobResponse;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.IngestionJobRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

@Service
public class IngestionJobService {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionJobService.class);

    private final IngestionJobRepository ingestionJobs;
    private final IngestionRunJpaRepository ingestionRuns;
    private final IngestionService ingestionService;

    public IngestionJobService(
            IngestionJobRepository ingestionJobs,
            IngestionRunJpaRepository ingestionRuns,
            IngestionService ingestionService) {
        this.ingestionJobs = ingestionJobs;
        this.ingestionRuns = ingestionRuns;
        this.ingestionService = ingestionService;
    }

    public IngestionJobResponse create(CreateIngestionJobRequest request) {
        validateCron(request.cronExpression());
        String datasetType = requiredUpper(request.datasetType(), "datasetType");
        validateDatasetType(datasetType);
        if (request.parameters() == null || !request.parameters().isObject()) {
            throw new IllegalArgumentException("parameters must be a JSON object");
        }
        short maxRetries = request.maxRetries() == null ? 0 : request.maxRetries();
        int timeoutSeconds = request.timeoutSeconds() == null ? 120 : request.timeoutSeconds();
        if (maxRetries < 0 || timeoutSeconds <= 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative and timeoutSeconds must be positive");
        }
        IngestionJobEntity entity = ingestionJobs.create(requiredUpper(request.dataSourceCode(), "dataSourceCode"),
                requiredUpper(request.code(), "code"), required(request.name(), "name"), datasetType,
                request.cronExpression(), request.parameters(), maxRetries, timeoutSeconds,
                request.active() == null || request.active());
        return IngestionJobResponse.from(entity);
    }

    public IngestionExecutionResponse runNow(UUID jobId) {
        IngestionJobEntity job = ingestionJobs.findEntityById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Ingestion job not found: " + jobId));
        if (!job.isActive()) {
            throw new IllegalArgumentException("Ingestion job is inactive: " + jobId);
        }
        return executeWithRetry(job, "MANUAL");
    }

    public List<IngestionJobResponse> listActive() {
        return ingestionJobs.findActiveEntities().stream().map(IngestionJobResponse::from).toList();
    }

    public void executeDueJobs() {
        Instant now = Instant.now();
        for (IngestionJobEntity job : ingestionJobs.findActiveEntities()) {
            if (!isDue(job, now)) {
                continue;
            }
            try {
                executeWithRetry(job, "SCHEDULED");
            } catch (RuntimeException exception) {
                LOGGER.warn("Scheduled ingestion job {} failed: {}", job.getCode(), exception.getMessage());
            }
        }
    }

    private IngestionExecutionResponse executeWithRetry(IngestionJobEntity job, String initialTrigger) {
        IngestionExecutionException lastFailure = null;
        for (int attempt = 0; attempt <= job.getMaxRetries(); attempt++) {
            try {
                return ingestionService.ingestJob(job, attempt == 0 ? initialTrigger : "RETRY");
            } catch (IngestionExecutionException exception) {
                lastFailure = exception;
            }
        }
        throw lastFailure;
    }

    private boolean isDue(IngestionJobEntity job, Instant now) {
        if (job.getCronExpression() == null || job.getCronExpression().isBlank()) {
            return false;
        }
        CronExpression cron = CronExpression.parse(job.getCronExpression());
        Optional<IngestionRunEntity> latest = ingestionRuns.findTopByIngestionJobIdOrderByStartedAtDesc(job.getId());
        Instant reference = latest.map(IngestionRunEntity::getStartedAt).orElse(job.getCreatedAt());
        Instant next = cron.next(reference.atZone(ZoneOffset.UTC)).toInstant();
        return !next.isAfter(now);
    }

    private void validateCron(String cronExpression) {
        if (cronExpression == null || cronExpression.isBlank()) {
            return;
        }
        if (!CronExpression.isValidExpression(cronExpression)) {
            throw new IllegalArgumentException("cronExpression is invalid");
        }
    }

    private void validateDatasetType(String datasetType) {
        if (!java.util.Set.of(
                "COMPANY", "SECURITY", "FINANCIAL_STATEMENT", "FINANCIAL_METRIC",
                "MARKET_PRICE", "MARKET_INDEX", "NEWS", "MACRO", "OTHER").contains(datasetType)) {
            throw new IllegalArgumentException("Unsupported datasetType: " + datasetType);
        }
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }

    private static String requiredUpper(String value, String field) {
        return required(value, field).toUpperCase(Locale.ROOT);
    }
}
