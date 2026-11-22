package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.service.ingestion.IngestionJobCatalogService;
import com.hethongdata.taichinh.service.llm.LlmPromptCatalog;
import com.hethongdata.taichinh.service.master.MasterDataCatalogService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/catalog")
public class CatalogAdminController {
    private final IngestionJobCatalogService jobs;
    private final MasterDataCatalogService master;
    private final LlmPromptCatalog prompts;

    public CatalogAdminController(IngestionJobCatalogService jobs, MasterDataCatalogService master,
            LlmPromptCatalog prompts) {
        this.jobs = jobs;
        this.master = master;
        this.prompts = prompts;
    }

    public record SeedResult(int count) {}

    @PostMapping("/ingestion-jobs/seed")
    public SeedResult seedJobs() { return new SeedResult(jobs.seed()); }

    @PostMapping("/master-data/seed")
    public SeedResult seedMasterData() { return new SeedResult(master.seed()); }

    @PostMapping("/news-prompts/seed")
    public SeedResult seedNewsPrompts() { return new SeedResult(prompts.seed()); }

    @PostMapping("/ingestion-jobs/retry-budget")
    public SeedResult initializeRetryBudgets() {
        return new SeedResult(jobs.initializeRetryBudgets());
    }
}
