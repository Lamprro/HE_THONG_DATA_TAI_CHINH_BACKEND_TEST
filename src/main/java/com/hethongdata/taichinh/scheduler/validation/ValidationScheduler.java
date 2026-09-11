package com.hethongdata.taichinh.scheduler.validation;

import com.hethongdata.taichinh.service.validation.ValidationJobService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Triggers validation of pending raw payloads when the validation scheduler is enabled. */
@Component
@ConditionalOnProperty(
        prefix = "financial.validation.scheduler",
        name = "enabled",
        havingValue = "true")
public class ValidationScheduler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ValidationScheduler.class);
    private final ValidationJobService jobs;

    public ValidationScheduler(ValidationJobService jobs) {
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${financial.validation.scheduler.poll-interval:60000}")
    public void validatePendingRawPayloads() {
        LOGGER.debug("Validation scheduler triggered: polling for pending raw payloads");
        var results = jobs.validatePending(50);
        if (!results.isEmpty()) {
            LOGGER.info("Validation scheduler processed {} pending payloads in this cycle", results.size());
        }
    }
}
