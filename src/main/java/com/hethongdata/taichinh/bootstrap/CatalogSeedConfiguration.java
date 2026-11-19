package com.hethongdata.taichinh.bootstrap;

import com.hethongdata.taichinh.common.AppParams;
import com.hethongdata.taichinh.repository.ingestion.IngestionJobRepository;
import com.hethongdata.taichinh.service.ingestion.IngestionJobCatalogService;
import com.hethongdata.taichinh.service.llm.LlmPromptCatalog;
import com.hethongdata.taichinh.service.master.MasterDataCatalogService;
import com.hethongdata.taichinh.service.validation.ValidationRuleCatalogService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * Startup seeders, each opt-in through its own property (all disabled by default). Bean names and
 * the run order (ingestion jobs, LLM prompts, master data, retry budget, validation rules) match the
 * former one-class-per-runner components.
 */
@Configuration(proxyBeanMethods = false)
public class CatalogSeedConfiguration {
    private static final Logger LOGGER = LoggerFactory.getLogger(CatalogSeedConfiguration.class);

    /** Mồi dữ liệu danh mục Ingestion Jobs vào database. */
    @Bean
    @Order(1)
    @ConditionalOnProperty(prefix = "financial.ingestion.catalog", name = "seed-enabled", havingValue = "true")
    ApplicationRunner ingestionJobCatalogSeeder(IngestionJobCatalogService catalogService) {
        return args -> LOGGER.info("Seeded {} Phase 1 ingestion job definitions", catalogService.seed());
    }

    @Bean
    @Order(2)
    @ConditionalOnProperty(name = "financial.llm.catalog.seed-enabled", havingValue = "true")
    ApplicationRunner llmPromptCatalogSeeder(LlmPromptCatalog catalog) {
        return args -> catalog.seed();
    }

    /** Mồi dữ liệu gốc công ty & mã cổ phiếu (master data) vào database. */
    @Bean
    @Order(3)
    @ConditionalOnProperty(prefix = "financial.master-data.catalog", name = "seed-enabled", havingValue = "true")
    ApplicationRunner masterDataCatalogSeeder(MasterDataCatalogService catalog) {
        return args -> LOGGER.info("Seeded {} master companies/securities", catalog.seed());
    }

    /** Explicit one-time migration of active job definitions to the standard retry budget. */
    @Bean
    @Order(4)
    @ConditionalOnProperty(prefix = "financial.ingestion.retry-budget", name = "initialize-enabled", havingValue = "true")
    ApplicationRunner retryBudgetInitializer(IngestionJobRepository ingestionJobs) {
        return args -> LOGGER.info(
                "Initialized retry budget 10 for {} ingestion jobs without changing activation state",
                ingestionJobs.initializeAllRetryBudgets(AppParams.DEFAULT_MAX_RETRIES));
    }

    /** Mồi dữ liệu danh mục quy tắc kiểm định (validation rules) vào database. */
    @Bean
    @Order(5)
    @ConditionalOnProperty(prefix = "financial.validation.catalog", name = "seed-enabled", havingValue = "true")
    ApplicationRunner validationRuleCatalogSeeder(ValidationRuleCatalogService catalog) {
        return args -> LOGGER.info("Seeded {} validation rules", catalog.seed());
    }
}
