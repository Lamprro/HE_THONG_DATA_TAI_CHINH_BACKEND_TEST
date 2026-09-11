package com.hethongdata.taichinh.bootstrap;

import com.hethongdata.taichinh.service.validation.ValidationRuleCatalogService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
        prefix = "financial.validation.catalog",
        name = "seed-enabled",
        havingValue = "true")
public class ValidationRuleCatalogSeeder implements ApplicationRunner {
    private static final Logger LOGGER = LoggerFactory.getLogger(ValidationRuleCatalogSeeder.class);
    private final ValidationRuleCatalogService catalog;

    public ValidationRuleCatalogSeeder(ValidationRuleCatalogService catalog) {
        this.catalog = catalog;
    }

    @Override
    public void run(ApplicationArguments args) {
        LOGGER.info("Seeded {} validation rules", catalog.seed());
    }
}
