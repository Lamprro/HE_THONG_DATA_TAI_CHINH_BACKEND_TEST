package com.hethongdata.taichinh.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.service.llm.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/admin/llm")
/** NEWS LLM tasks, run audit and Gemini routing diagnostics. */
public class LlmAdminController {
    private final LlmPromptCatalog catalog;
    private final NewsLlmService service;
    private final LlmRunStore store;
    private final org.springframework.beans.factory.ObjectProvider<GeminiLlmGateway> gemini;
    public LlmAdminController(LlmPromptCatalog catalog,NewsLlmService service,LlmRunStore store,
            org.springframework.beans.factory.ObjectProvider<GeminiLlmGateway> gemini) {
        this.catalog=catalog;this.service=service;this.store=store;this.gemini=gemini;
    }
    @GetMapping("/gemini/configuration")
    public Map<String,Object> geminiConfiguration() {return gemini.getObject().configuration();}
    @GetMapping("/gemini/models")
    public JsonNode geminiModels() throws Exception {return gemini.getObject().availableModels();}
    @GetMapping("/templates")
    public List<LlmPromptCatalog.Template> templates() {return catalog.activeTemplates();}
    @GetMapping("/news/candidates")
    public List<UUID> candidates(@RequestParam(defaultValue="10") int limit) {return service.candidates(limit);}
    @GetMapping("/news/{articleId}/preview")
    public JsonNode preview(@PathVariable UUID articleId,@RequestParam(defaultValue="NEWS_SUMMARY") String task) {
        return service.preview(articleId,task);
    }
    @PostMapping("/news/{articleId}/execute")
    public Map<String,LlmRunStore.Outcome> execute(@PathVariable UUID articleId) {return service.executeAll(articleId);}
    @PostMapping("/news/{articleId}/tasks/{task}/execute")
    public LlmRunStore.Outcome executeTask(@PathVariable UUID articleId,@PathVariable String task) {return service.execute(articleId,task);}
    @GetMapping("/news/{articleId}/results")
    public List<JsonNode> results(@PathVariable UUID articleId) {return store.results(articleId);}
    @GetMapping("/runs/{runId}")
    public JsonNode run(@PathVariable UUID runId) {return store.run(runId);}
    @PostMapping("/runs/{runId}/revalidate")
    public LlmRunStore.Outcome revalidate(@PathVariable UUID runId) {return store.revalidate(runId);}
    @PostMapping("/validation/pending")
    public List<LlmRunStore.Outcome> validatePending(@RequestParam(defaultValue="5") int limit) {return service.validatePending(limit);}
}
