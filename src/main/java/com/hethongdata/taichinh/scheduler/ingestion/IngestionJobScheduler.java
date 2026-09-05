package com.hethongdata.taichinh.scheduler.ingestion;

import com.hethongdata.taichinh.service.ingestion.IngestionJobService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls for due ingestion jobs. Job definitions and execution rules belong to the ingestion
 * services.
 */
@Component
@ConditionalOnProperty(
        prefix = "financial.ingestion.scheduler",
        name = "enabled",
        havingValue = "true")
public class IngestionJobScheduler {

    private final IngestionJobService ingestionJobService;

    public IngestionJobScheduler(IngestionJobService ingestionJobService) {
        this.ingestionJobService = ingestionJobService;
    }

    @Scheduled(fixedDelayString = "${financial.ingestion.scheduler.poll-interval:60000}")
    public void poll() {
        ingestionJobService.executeDueJobs();
    }
}
