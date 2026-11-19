package com.hethongdata.taichinh.controller.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.llm.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
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
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final ChecksumService hashes;
    public LlmAdminController(LlmPromptCatalog catalog,NewsLlmService service,LlmRunStore store,
            ObjectProvider<GeminiLlmGateway> gemini,JdbcTemplate db,ObjectMapper json,ChecksumService hashes) {
        this.catalog=catalog;this.service=service;this.store=store;this.gemini=gemini;
        this.db=db;this.json=json;this.hashes=hashes;
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
    @GetMapping("/api/admin/llm/runs/{runId}")
    public JsonNode run(@PathVariable UUID runId) {return store.run(runId);}
    @PostMapping("/api/admin/llm/runs/{runId}/revalidate")
    public LlmRunStore.Outcome revalidate(@PathVariable UUID runId) {return store.revalidate(runId);}
    @PostMapping("/api/admin/llm/validation/pending")
    public List<LlmRunStore.Outcome> validatePending(@RequestParam(defaultValue="5") int limit) {return service.validatePending(limit);}

    // --- /api/admin/prompts

    public record PromptVersionRequest(@NotNull UUID basedOnId,@NotBlank @Size(max=40) String version,@NotBlank @Size(max=100000) String prompt,@NotBlank @Size(max=2000) String reason) {}
    public record PromptEnableRequest(boolean enabled,@NotBlank @Size(max=2000) String reason) {}
    @GetMapping("/api/admin/prompts") public List<Map<String,Object>> prompts() {
      return db.queryForList("select id,task_code,version,input_domain,description,checksum,enabled,created_at from llm_prompt_templates order by task_code,created_at desc,id");
    }
    @GetMapping("/api/admin/prompts/{id}") public JsonNode prompt(@PathVariable UUID id) {
      var rows=db.queryForList("select to_jsonb(p)::text from llm_prompt_templates p where id=?",String.class,id);
      if(rows.isEmpty()) throw new IllegalArgumentException("Prompt not found");
      try {return json.readTree(rows.getFirst());}catch(Exception e){throw new IllegalStateException("Invalid stored prompt");}
    }
    @PostMapping("/api/admin/prompts") @Transactional public Map<String,Object> createPrompt(@Valid @RequestBody PromptVersionRequest request) {
      var base=prompt(request.basedOnId());String task=base.path("task_code").asText();lockPrompt(task);
      UUID id=UUID.randomUUID();var resource=json.createObjectNode().put("task_code",task).put("version",request.version()).put("system_prompt",request.prompt());
      resource.set("request_schema",base.path("request_schema"));resource.set("response_schema",base.path("response_schema"));
      String checksum=hashes.sha256(new LlmJson(json).canonical(resource).toString());
      int n=db.update("insert into llm_prompt_templates(id,task_code,version,input_domain,description,system_prompt,request_schema,response_schema,checksum,enabled) values (?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,false) on conflict(task_code,version) do nothing",
        id,task,request.version(),base.path("input_domain").asText(),base.path("description").asText(),request.prompt(),base.path("request_schema").toString(),base.path("response_schema").toString(),checksum);
      if(n!=1) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Prompt version already exists; existing versions are immutable");
      auditPrompt("PROMPT_CREATED",id,request.reason());return Map.of("id",id,"version",request.version(),"enabled",false,"checksum",checksum);
    }
    @PatchMapping("/api/admin/prompts/{id}/enabled") @Transactional public Map<String,Object> setPromptEnabled(@PathVariable UUID id,@Valid @RequestBody PromptEnableRequest request) {
      String task=prompt(id).path("task_code").asText();lockPrompt(task);
      if(request.enabled()) db.update("update llm_prompt_templates set enabled=false where task_code=? and enabled",task);
      db.update("update llm_prompt_templates set enabled=? where id=?",request.enabled(),id);
      auditPrompt(request.enabled()?"PROMPT_ENABLED":"PROMPT_DISABLED",id,request.reason());return Map.of("id",id,"enabled",request.enabled());
    }
    private void lockPrompt(String task) {db.queryForObject("select 1 from pg_advisory_xact_lock(hashtext(?))",Integer.class,"llm-prompt:"+task);}
    private void auditPrompt(String type,UUID id,String reason) {db.update("insert into admin_operation_events(id,event_type,target_id,reason) values (?,?,?,?)",UUID.randomUUID(),type,id,reason);}
}
