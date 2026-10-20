package com.hethongdata.taichinh.controller.admin;

import com.fasterxml.jackson.databind.*;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Operator edits create disabled immutable versions; native input/output schemas are retained. */
@RestController @RequestMapping("/api/admin/prompts")
public class AdminPromptController {
  private final JdbcTemplate db;private final ObjectMapper json;private final ChecksumService hashes;
  public AdminPromptController(JdbcTemplate db,ObjectMapper json,ChecksumService hashes) {this.db=db;this.json=json;this.hashes=hashes;}
  public record VersionRequest(@NotNull UUID basedOnId,@NotBlank @Size(max=40) String version,@NotBlank @Size(max=100000) String prompt,@NotBlank @Size(max=2000) String reason) {}
  public record EnableRequest(boolean enabled,@NotBlank @Size(max=2000) String reason) {}
  @GetMapping public List<Map<String,Object>> list() {
    return db.queryForList("select id,task_code,version,input_domain,description,checksum,enabled,created_at from llm_prompt_templates order by task_code,created_at desc,id");
  }
  @GetMapping("/{id}") public JsonNode detail(@PathVariable UUID id) {
    var rows=db.queryForList("select to_jsonb(p)::text from llm_prompt_templates p where id=?",String.class,id);
    if(rows.isEmpty()) throw new IllegalArgumentException("Prompt not found");
    try {return json.readTree(rows.getFirst());}catch(Exception e){throw new IllegalStateException("Invalid stored prompt");}
  }
  @PostMapping @Transactional public Map<String,Object> create(@Valid @RequestBody VersionRequest request) {
    var base=detail(request.basedOnId());String task=base.path("task_code").asText();lock(task);
    UUID id=UUID.randomUUID();var resource=json.createObjectNode().put("task_code",task).put("version",request.version()).put("system_prompt",request.prompt());
    resource.set("request_schema",base.path("request_schema"));resource.set("response_schema",base.path("response_schema"));
    String checksum=hashes.sha256(new com.hethongdata.taichinh.service.llm.LlmJson(json).canonical(resource).toString());
    int n=db.update("insert into llm_prompt_templates(id,task_code,version,input_domain,description,system_prompt,request_schema,response_schema,checksum,enabled) values (?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,false) on conflict(task_code,version) do nothing",
      id,task,request.version(),base.path("input_domain").asText(),base.path("description").asText(),request.prompt(),base.path("request_schema").toString(),base.path("response_schema").toString(),checksum);
    if(n!=1) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Prompt version already exists; existing versions are immutable");
    audit("PROMPT_CREATED",id,request.reason());return Map.of("id",id,"version",request.version(),"enabled",false,"checksum",checksum);
  }
  @PatchMapping("/{id}/enabled") @Transactional public Map<String,Object> enabled(@PathVariable UUID id,@Valid @RequestBody EnableRequest request) {
    String task=detail(id).path("task_code").asText();lock(task);
    if(request.enabled()) db.update("update llm_prompt_templates set enabled=false where task_code=? and enabled",task);
    db.update("update llm_prompt_templates set enabled=? where id=?",request.enabled(),id);
    audit(request.enabled()?"PROMPT_ENABLED":"PROMPT_DISABLED",id,request.reason());return Map.of("id",id,"enabled",request.enabled());
  }
  private void lock(String task) {db.queryForObject("select 1 from pg_advisory_xact_lock(hashtext(?))",Integer.class,"llm-prompt:"+task);}
  private void audit(String type,UUID id,String reason) {db.update("insert into admin_operation_events(id,event_type,target_id,reason) values (?,?,?,?)",UUID.randomUUID(),type,id,reason);}
}
