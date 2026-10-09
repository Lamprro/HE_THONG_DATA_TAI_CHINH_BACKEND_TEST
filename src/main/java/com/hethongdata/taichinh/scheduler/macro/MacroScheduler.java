package com.hethongdata.taichinh.scheduler.macro;

import com.hethongdata.taichinh.service.ingestion.IngestionJobService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Macro-only polling uses the existing job cron/retry policy without enabling unrelated jobs. */
@Component
@ConditionalOnProperty(prefix = "financial.macro.scheduler", name = "enabled", havingValue = "true")
public class MacroScheduler {
    private final IngestionJobService jobs;

    public MacroScheduler(IngestionJobService jobs) {
        this.jobs = jobs;
    }

    @Scheduled(fixedDelayString = "${financial.macro.scheduler.poll-interval:60000}")
    public void poll() {
        jobs.executeDueMacroJobs();
    }
}
