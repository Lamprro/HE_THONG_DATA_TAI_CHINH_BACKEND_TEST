package com.hethongdata.taichinh.scheduler.validation;

import com.hethongdata.taichinh.service.validation.ValidationJobService;

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
    private final ValidationJobService jobs;

    public ValidationScheduler(ValidationJobService jobs) {
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${financial.validation.scheduler.poll-interval:60000}")
    public void validatePendingRawPayloads() {
        jobs.validatePending(50);
    }
}
