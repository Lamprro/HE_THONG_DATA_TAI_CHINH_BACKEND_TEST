package com.hethongdata.taichinh.bootstrap;

import com.hethongdata.taichinh.common.AppParams;
import com.hethongdata.taichinh.repository.ingestion.IngestionJobRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Explicit one-time migration of active job definitions to the standard retry budget. */
@Component
@ConditionalOnProperty(
        prefix = "financial.ingestion.retry-budget",
        name = "initialize-enabled",
        havingValue = "true")
public class RetryBudgetInitializer implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(RetryBudgetInitializer.class);
    private final IngestionJobRepository ingestionJobs;

    public RetryBudgetInitializer(IngestionJobRepository ingestionJobs) {
        this.ingestionJobs = ingestionJobs;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOGGER.info(
                "Initialized retry budget 10 for {} ingestion jobs without changing activation state",
                ingestionJobs.initializeAllRetryBudgets(AppParams.DEFAULT_MAX_RETRIES));
    }
}
