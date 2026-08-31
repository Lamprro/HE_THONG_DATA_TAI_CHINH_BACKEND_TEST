package com.hethongdata.taichinh.service.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.common.AppParams;
import com.hethongdata.taichinh.dto.ingestion.CreateIngestionJobRequest;
import com.hethongdata.taichinh.dto.ingestion.IngestionExecutionResponse;
import com.hethongdata.taichinh.dto.ingestion.IngestionJobResponse;
import com.hethongdata.taichinh.dto.ingestion.UpdateIngestionJobActivationRequest;
import com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity;
import com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity;
import com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository;
import com.hethongdata.taichinh.repository.IngestionJobRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
    private final RetryBudgetService retryBudgetService;

    public IngestionJobService(
            IngestionJobRepository ingestionJobs,
            IngestionRunJpaRepository ingestionRuns,
            IngestionService ingestionService,
            RetryBudgetService retryBudgetService) {
        this.ingestionJobs = ingestionJobs;
        this.ingestionRuns = ingestionRuns;
        this.ingestionService = ingestionService;
        this.retryBudgetService = retryBudgetService;
    }

    public IngestionJobResponse create(CreateIngestionJobRequest request) {
        validateCron(request.cronExpression());
        String datasetType = AppParams.requiredUpper(request.datasetType(), "datasetType");
        validateDatasetType(datasetType);
        if (request.parameters() == null || !request.parameters().isObject()) {
            throw new IllegalArgumentException("parameters must be a JSON object");
        }
        short maxRetries = request.maxRetries() == null ? AppParams.DEFAULT_MAX_RETRIES : request.maxRetries();
        if (maxRetries != AppParams.DEFAULT_MAX_RETRIES) {
            throw new IllegalArgumentException("maxRetries must be " + AppParams.DEFAULT_MAX_RETRIES + " for this retry policy");
        }
        int timeoutSeconds = request.timeoutSeconds() == null ? AppParams.DEFAULT_INGESTION_TIMEOUT_SECONDS : request.timeoutSeconds();
        if (maxRetries < 0 || timeoutSeconds <= 0) {
            throw new IllegalArgumentException("maxRetries must be non-negative and timeoutSeconds must be positive");
        }
        IngestionJobEntity entity = ingestionJobs.create(AppParams.requiredUpper(request.dataSourceCode(), "dataSourceCode"),
                AppParams.requiredUpper(request.code(), "code"), AppParams.requiredTrimmed(request.name(), "name"), datasetType,
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
        return executeWithBudget(job, "MANUAL");
    }

    public List<IngestionJobResponse> listActive() {
        return ingestionJobs.findActiveEntities().stream().map(IngestionJobResponse::from).toList();
    }

    public IngestionJobResponse setActive(UUID jobId, UpdateIngestionJobActivationRequest request) {
        IngestionJobEntity job = ingestionJobs.setActive(jobId, request.active());
        if (request.active()) {
            retryBudgetService.resetAfterSuccess(job);
        }
        return IngestionJobResponse.from(job);
    }

    /** Polls active jobs; cron evaluation uses the last recorded run in UTC. */
    public void executeDueJobs() {
        Instant now = Instant.now();
        for (IngestionJobEntity job : ingestionJobs.findActiveEntities()) {
            if (!isDue(job, now)) {
                continue;
            }
            try {
                executeWithBudget(job, "SCHEDULED");
            } catch (RuntimeException exception) {
                LOGGER.warn("Scheduled ingestion job {} failed: {}", job.getCode(), exception.getMessage());
            }
        }
    }

    /** One invocation makes one transport call. Failure is budgeted across later scheduled/manual invocations. */
    private IngestionExecutionResponse executeWithBudget(IngestionJobEntity job, String triggerType) {
        try {
            IngestionExecutionResponse response = ingestionService.ingestJob(job, triggerType);
            retryBudgetService.resetAfterSuccess(job);
            return response;
        } catch (IngestionExecutionException exception) {
            Long remaining = retryBudgetService.consumeFailedAttempt(job);
            if (remaining != null && remaining <= 0) {
                ingestionJobs.disableAfterRetryBudgetExhausted(job.getId());
                LOGGER.warn("Disabled ingestion job {} after exhausting its Redis retry budget", job.getCode());
            }
            throw exception;
        }
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
        if (!AppParams.INGESTION_DATASET_TYPES.contains(datasetType)) {
            throw new IllegalArgumentException("Unsupported datasetType: " + datasetType);
        }
    }

}
