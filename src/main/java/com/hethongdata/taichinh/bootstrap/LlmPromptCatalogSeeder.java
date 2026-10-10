package com.hethongdata.taichinh.bootstrap;

import com.hethongdata.taichinh.service.llm.LlmPromptCatalog;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="financial.llm.catalog.seed-enabled",havingValue="true")
public class LlmPromptCatalogSeeder implements ApplicationRunner {
    private final LlmPromptCatalog catalog;
    public LlmPromptCatalogSeeder(LlmPromptCatalog catalog) {this.catalog=catalog;}
    @Override public void run(ApplicationArguments args) {catalog.seed();}
}
