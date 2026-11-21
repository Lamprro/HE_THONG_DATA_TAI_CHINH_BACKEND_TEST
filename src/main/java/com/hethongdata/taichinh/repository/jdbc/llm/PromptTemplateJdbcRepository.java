package com.hethongdata.taichinh.repository.jdbc.llm;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PromptTemplateJdbcRepository {
  public record PromptRecord(UUID id, String taskCode, String version, String systemPrompt,
                             String requestSchema, String responseSchema, String checksum) {}

  public record SummaryRecord(UUID id, String version, boolean enabled, String checksum, String description) {}

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

  public int insertActiveVersion(UUID id, String task, String version, String inputDomain, String description,
      String systemPrompt, String requestSchema, String responseSchema, String checksum) {
    return db.update("""
        INSERT INTO llm_prompt_templates(id,task_code,version,input_domain,description,system_prompt,
            request_schema,response_schema,checksum,enabled)
        VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb,?,true) ON CONFLICT(task_code,version) DO NOTHING
        """, id, task, version, inputDomain, description, systemPrompt, requestSchema, responseSchema, checksum);
  }

  public void disableTaskPrompts(String task) {
    db.update("update llm_prompt_templates set enabled=false where task_code=? and enabled", task);
  }

  public void disableAllTaskPrompts(String task) {
    db.update("UPDATE llm_prompt_templates SET enabled=false WHERE task_code=?", task);
  }

  public int setEnabled(UUID id, boolean enabled) {
    return db.update("update llm_prompt_templates set enabled=? where id=?", enabled, id);
  }

  public void lockPrompt(String task) {
    db.queryForObject("select 1 from pg_advisory_xact_lock(hashtext(?))", Integer.class, "llm-prompt:" + task);
  }

  public void lockRecoveryPrompt() {
    db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "recovery-prompt");
  }

  public Optional<String> findChecksum(String task, String version) {
    var list = db.queryForList("SELECT checksum FROM llm_prompt_templates WHERE task_code=? AND version=?",
        String.class, task, version);
    return list.isEmpty() ? Optional.empty() : Optional.of(list.getFirst());
  }

  public List<PromptRecord> findActiveByDomain(String domain) {
    return db.query("SELECT * FROM llm_prompt_templates WHERE enabled AND input_domain=? ORDER BY task_code",
        (rs, n) -> new PromptRecord(
            rs.getObject("id", UUID.class), rs.getString("task_code"), rs.getString("version"),
            rs.getString("system_prompt"), rs.getString("request_schema"),
            rs.getString("response_schema"), rs.getString("checksum")),
        domain);
  }

  public Optional<PromptRecord> findActiveByTaskAndDomain(String task, String domain) {
    return db.query("SELECT * FROM llm_prompt_templates WHERE task_code=? AND enabled AND input_domain=?",
        (rs, n) -> new PromptRecord(
            rs.getObject("id", UUID.class), task, rs.getString("version"),
            rs.getString("system_prompt"), rs.getString("request_schema"),
            rs.getString("response_schema"), rs.getString("checksum")),
        task, domain).stream().findFirst();
  }

  public Optional<PromptRecord> findActiveByTask(String task) {
    return db.query("SELECT * FROM llm_prompt_templates WHERE task_code=? AND enabled",
        (rs, n) -> new PromptRecord(
            rs.getObject("id", UUID.class), task, rs.getString("version"),
            rs.getString("system_prompt"), rs.getString("request_schema"),
            rs.getString("response_schema"), rs.getString("checksum")),
        task).stream().findFirst();
  }

  public boolean existsByIdAndTask(UUID id, String task) {
    Integer count = db.queryForObject("SELECT count(*) FROM llm_prompt_templates WHERE id=? AND task_code=?",
        Integer.class, id, task);
    return count != null && count == 1;
  }

  public List<SummaryRecord> listSummariesByTask(String task) {
    return db.query(
        "SELECT id,version,enabled,checksum,description FROM llm_prompt_templates WHERE task_code=? ORDER BY created_at DESC,id",
        (r, n) -> new SummaryRecord(
            r.getObject(1, UUID.class), r.getString(2), r.getBoolean(3), r.getString(4), r.getString(5)),
        task);
  }
}
