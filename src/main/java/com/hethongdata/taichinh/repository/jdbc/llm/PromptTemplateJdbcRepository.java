package com.hethongdata.taichinh.repository.jdbc.llm;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PromptTemplateJdbcRepository {
  private final JdbcTemplate db;

  public PromptTemplateJdbcRepository(JdbcTemplate db) {
    this.db = db;
  }

  public List<Map<String, Object>> findAllSummary() {
    return db.queryForList("select id,task_code,version,input_domain,description,checksum,enabled,created_at from llm_prompt_templates order by task_code,created_at desc,id");
  }

  public Optional<String> findJsonById(UUID id) {
    var rows = db.queryForList("select to_jsonb(p)::text from llm_prompt_templates p where id=?", String.class, id);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public int insertVersion(UUID id, String task, String version, String inputDomain, String description,
      String systemPrompt, String requestSchema, String responseSchema, String checksum) {
    return db.update(
        "insert into llm_prompt_templates(id,task_code,version,input_domain,description,system_prompt,request_schema,response_schema,checksum,enabled) values (?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?,false) on conflict(task_code,version) do nothing",
        id, task, version, inputDomain, description, systemPrompt, requestSchema, responseSchema, checksum);
  }

  public void disableTaskPrompts(String task) {
    db.update("update llm_prompt_templates set enabled=false where task_code=? and enabled", task);
  }

  public int setEnabled(UUID id, boolean enabled) {
    return db.update("update llm_prompt_templates set enabled=? where id=?", enabled, id);
  }

  public void lockPrompt(String task) {
    db.queryForObject("select 1 from pg_advisory_xact_lock(hashtext(?))", Integer.class, "llm-prompt:" + task);
  }
}
