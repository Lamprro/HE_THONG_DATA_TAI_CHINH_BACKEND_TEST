package com.hethongdata.taichinh.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.dto.admin.AdminConsoleDtos.HistoryPage;
import com.hethongdata.taichinh.dto.admin.AdminConsoleDtos.LlmRunSummary;
import com.hethongdata.taichinh.service.llm.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/**
 * NEWS LLM tasks, run audit and Gemini routing diagnostics ({@code /api/admin/llm/**}), plus prompt
 * template versions ({@code /api/admin/prompts/**}): operator edits create disabled immutable
 * versions; native input/output schemas are retained.
 */
@RestController
public class LlmAdminController {
    private final LlmPromptCatalog catalog;
    private final NewsLlmService service;
    private final LlmRunStore store;
    private final ObjectProvider<GeminiLlmGateway> gemini;
    private final PromptAdminService promptAdmin;

    public LlmAdminController(LlmPromptCatalog catalog, NewsLlmService service, LlmRunStore store,
            ObjectProvider<GeminiLlmGateway> gemini, PromptAdminService promptAdmin) {
        this.catalog = catalog;
        this.service = service;
        this.store = store;
        this.gemini = gemini;
        this.promptAdmin = promptAdmin;
    }

    // --- /api/admin/llm

    @GetMapping("/api/admin/llm/gemini/configuration")
    public Map<String,Object> geminiConfiguration() {return gemini.getObject().configuration();}
    @GetMapping("/api/admin/llm/gemini/models")
    public JsonNode geminiModels() throws Exception {return gemini.getObject().availableModels();}
    @GetMapping("/api/admin/llm/templates")
    public List<LlmPromptCatalog.Template> templates() {return catalog.activeTemplates();}
    @GetMapping("/api/admin/llm/news/candidates")
    public List<UUID> candidates(@RequestParam(defaultValue="10") int limit) {return service.candidates(limit);}
    @GetMapping("/api/admin/llm/news/{articleId}/preview")
    public JsonNode preview(@PathVariable UUID articleId,@RequestParam(defaultValue="NEWS_SUMMARY") String task) {
        return service.preview(articleId,task);
    }
    @PostMapping("/api/admin/llm/news/{articleId}/execute")
    public Map<String,LlmRunStore.Outcome> execute(@PathVariable UUID articleId) {return service.executeAll(articleId);}
    @PostMapping("/api/admin/llm/news/{articleId}/tasks/{task}/execute")
    public LlmRunStore.Outcome executeTask(@PathVariable UUID articleId,@PathVariable String task) {return service.execute(articleId,task);}
    @GetMapping("/api/admin/llm/news/{articleId}/results")
    public List<JsonNode> results(@PathVariable UUID articleId) {return store.results(articleId);}
    @GetMapping("/api/admin/llm/runs")
    public HistoryPage<LlmRunSummary> runs(@RequestParam(defaultValue="") String task,
            @RequestParam(defaultValue="") String status, @RequestParam(defaultValue="") String search,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="10") int size) {
        if (page < 0 || size < 1 || size > 500 || search.length() > 160)
            throw new IllegalArgumentException("Invalid LLM history filters");
        return store.history(task.trim(), status.trim(), search.trim(), page, size);
    }
    @GetMapping("/api/admin/llm/runs/{runId}")
    public JsonNode run(@PathVariable UUID runId) {return store.run(runId);}
    @PostMapping("/api/admin/llm/runs/{runId}/revalidate")
    public LlmRunStore.Outcome revalidate(@PathVariable UUID runId) {return store.revalidate(runId);}
    @PostMapping("/api/admin/llm/validation/pending")
    public List<LlmRunStore.Outcome> validatePending(@RequestParam(defaultValue="5") int limit) {return service.validatePending(limit);}

    // --- /api/admin/prompts

    public record PromptVersionRequest(@NotNull UUID basedOnId,@NotBlank @Size(max=40) String version,@NotBlank @Size(max=100000) String prompt,@NotBlank @Size(max=2000) String reason) {}
    public record PromptEnableRequest(boolean enabled,@NotBlank @Size(max=2000) String reason) {}

    @GetMapping("/api/admin/prompts")
    public List<Map<String,Object>> prompts() {
      return promptAdmin.prompts();
    }

    @GetMapping("/api/admin/prompts/{id}")
    public JsonNode prompt(@PathVariable UUID id) {
      return promptAdmin.prompt(id);
    }

    @PostMapping("/api/admin/prompts")
    public Map<String,Object> createPrompt(@Valid @RequestBody PromptVersionRequest request) {
      return promptAdmin.createPrompt(request.basedOnId(), request.version(), request.prompt(), request.reason());
    }

    @PatchMapping("/api/admin/prompts/{id}/enabled")
    public Map<String,Object> setPromptEnabled(@PathVariable UUID id,@Valid @RequestBody PromptEnableRequest request) {
      return promptAdmin.setPromptEnabled(id, request.enabled(), request.reason());
    }
}
