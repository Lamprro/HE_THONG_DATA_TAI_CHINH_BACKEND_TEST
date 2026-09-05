package com.hethongdata.taichinh.bootstrap;

import com.hethongdata.taichinh.service.ingestion.IngestionJobCatalogService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Enables an explicit, repeatable catalog seed without running any ingestion job. */
@Component
@ConditionalOnProperty(
        prefix = "financial.ingestion.catalog",
        name = "seed-enabled",
        havingValue = "true")
public class IngestionJobCatalogSeeder implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionJobCatalogSeeder.class);
    private final IngestionJobCatalogService catalogService;

    public IngestionJobCatalogSeeder(IngestionJobCatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOGGER.info("Seeded {} Phase 1 ingestion job definitions", catalogService.seed());
    }
}
