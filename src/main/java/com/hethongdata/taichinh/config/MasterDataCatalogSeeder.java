package com.hethongdata.taichinh.config;

import com.hethongdata.taichinh.service.master.MasterDataCatalogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Deliberate starter-data write, never executed by a normal application startup. */
@Component
@ConditionalOnProperty(prefix = "financial.master-data.catalog", name = "seed-enabled", havingValue = "true")
public class MasterDataCatalogSeeder implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(MasterDataCatalogSeeder.class);
    private final MasterDataCatalogService catalog;
    public MasterDataCatalogSeeder(MasterDataCatalogService catalog) { this.catalog = catalog; }
    @Override public void run(ApplicationArguments args) { LOGGER.info("Seeded {} master companies/securities", catalog.seed()); }
}
