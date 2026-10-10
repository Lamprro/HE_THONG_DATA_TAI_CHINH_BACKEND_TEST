package com.hethongdata.taichinh.service.forecast;

import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.llm.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ForecastPromptService {
  public static final String TASK = "FINANCIAL_SCENARIOS";
  private final JdbcTemplate db;
  private final LlmJson json;
  private final ChecksumService hashes;

  public ForecastPromptService(JdbcTemplate db, LlmJson json, ChecksumService hashes) {
    this.db = db;
    this.json = json;
    this.hashes = hashes;
  }

  @Transactional
  public int seed() {
    try (var stream = new ClassPathResource("llm/financial_scenarios.json").getInputStream()) {
      var t = json.mapper().readTree(stream);
      String sum = hashes.sha256(t.toString());
      db.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "llm-prompt:" + TASK);
      var existing =
          db.queryForList(
              "SELECT checksum FROM llm_prompt_templates WHERE task_code=? AND version=?",
              String.class,
              TASK,
              t.path("version").asText());
      if (!existing.isEmpty()) {
        if (!existing.getFirst().equals(sum))
          throw new IllegalStateException("Immutable forecast template changed: increase version");
        return 0;
      }
      db.update(
          "UPDATE llm_prompt_templates SET enabled=false WHERE task_code=? AND enabled", TASK);
      return db.update(
          "INSERT INTO"
              + " llm_prompt_templates(id,task_code,version,input_domain,description,system_prompt,request_schema,response_schema,checksum,enabled)"
              + " VALUES (?,?,?,'FINANCIAL',?,?,?::jsonb,?::jsonb,?,true)",
          UUID.randomUUID(),
          TASK,
          t.path("version").asText(),
          t.path("description").asText(),
          t.path("system_prompt").asText(),
          t.path("request_schema").toString(),
          t.path("response_schema").toString(),
          sum);
    } catch (java.io.IOException e) {
      throw new IllegalStateException("Cannot load forecast template", e);
    }
  }

  public LlmPromptCatalog.Template active() {
    return db
        .query(
            "SELECT * FROM llm_prompt_templates WHERE task_code=? AND input_domain='FINANCIAL' AND"
                + " enabled",
            (r, n) ->
                new LlmPromptCatalog.Template(
                    r.getObject("id", UUID.class),
                    TASK,
                    r.getString("version"),
                    r.getString("system_prompt"),
                    json.read(r.getString("request_schema")),
                    json.read(r.getString("response_schema")),
                    r.getString("checksum")),
            TASK)
        .stream()
        .findFirst()
        .orElseThrow(
            () -> new IllegalArgumentException("Forecast template not seeded or disabled"));
  }

  @Transactional
  public com.hethongdata.taichinh.dto.forecast.ForecastDtos.TemplateState enabled(
      UUID id, boolean enabled) {
    db.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "llm-prompt:" + TASK);
    if (db.queryForObject(
            "SELECT count(*) FROM llm_prompt_templates WHERE id=? AND task_code=?",
            Integer.class,
            id,
            TASK)
        != 1) throw new IllegalArgumentException("Forecast template not found");
    if (enabled)
      db.update(
          "UPDATE llm_prompt_templates SET enabled=false WHERE task_code=? AND enabled", TASK);
    db.update("UPDATE llm_prompt_templates SET enabled=? WHERE id=?", enabled, id);
    return new com.hethongdata.taichinh.dto.forecast.ForecastDtos.TemplateState(id, enabled);
  }

  public List<com.hethongdata.taichinh.dto.forecast.ForecastDtos.TemplateSummary> list() {
    return db.query(
        "SELECT id,version,enabled,checksum,description FROM llm_prompt_templates WHERE task_code=?"
            + " ORDER BY created_at DESC,id",
        (r, n) ->
            new com.hethongdata.taichinh.dto.forecast.ForecastDtos.TemplateSummary(
                r.getObject(1, UUID.class),
                r.getString(2),
                r.getBoolean(3),
                r.getString(4),
                r.getString(5)),
        TASK);
  }
}
