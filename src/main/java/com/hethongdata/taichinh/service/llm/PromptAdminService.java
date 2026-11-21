package com.hethongdata.taichinh.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.repository.jdbc.admin.AdminAuditJdbcRepository;
import com.hethongdata.taichinh.repository.jdbc.llm.PromptTemplateJdbcRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class PromptAdminService {
  private final PromptTemplateJdbcRepository repository;
  private final AdminAuditJdbcRepository auditRepository;
  private final ObjectMapper json;
  private final ChecksumService hashes;

  public PromptAdminService(PromptTemplateJdbcRepository repository,
      AdminAuditJdbcRepository auditRepository,
      ObjectMapper json,
      ChecksumService hashes) {
    this.repository = repository;
    this.auditRepository = auditRepository;
    this.json = json;
    this.hashes = hashes;
  }

  public List<Map<String, Object>> prompts() {
    return repository.findAllSummary();
  }

  public JsonNode prompt(UUID id) {
    String raw = repository.findJsonById(id)
        .orElseThrow(() -> new IllegalArgumentException("Prompt not found"));
    try {
      return json.readTree(raw);
    } catch (Exception e) {
      throw new IllegalStateException("Invalid stored prompt");
    }
  }

  @Transactional
  public Map<String, Object> createPrompt(UUID basedOnId, String version, String prompt, String reason) {
    var base = prompt(basedOnId);
    String task = base.path("task_code").asText();
    repository.lockPrompt(task);
    UUID id = UUID.randomUUID();
    var resource = json.createObjectNode()
        .put("task_code", task)
        .put("version", version)
        .put("system_prompt", prompt);
    resource.set("request_schema", base.path("request_schema"));
    resource.set("response_schema", base.path("response_schema"));
    String checksum = hashes.sha256(new LlmJson(json).canonical(resource).toString());
    int n = repository.insertVersion(id, task, version, base.path("input_domain").asText(),
        base.path("description").asText(), prompt, base.path("request_schema").toString(),
        base.path("response_schema").toString(), checksum);
    if (n != 1) {
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Prompt version already exists; existing versions are immutable");
    }
    auditRepository.recordEvent(UUID.randomUUID(), "PROMPT_CREATED", id, reason);
    return Map.of("id", id, "version", version, "enabled", false, "checksum", checksum);
  }

  @Transactional
  public Map<String, Object> setPromptEnabled(UUID id, boolean enabled, String reason) {
    String task = prompt(id).path("task_code").asText();
    repository.lockPrompt(task);
    if (enabled) {
      repository.disableTaskPrompts(task);
    }
    repository.setEnabled(id, enabled);
    auditRepository.recordEvent(UUID.randomUUID(), enabled ? "PROMPT_ENABLED" : "PROMPT_DISABLED", id, reason);
    return Map.of("id", id, "enabled", enabled);
  }
}
