package com.hethongdata.taichinh.service.news.recovery;

import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.llm.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NewsRecoveryCatalog {
  public static final String TASK = "NEWS_CONTENT_RECOVERY";
  private final JdbcTemplate db;
  private final LlmJson json;
  private final ChecksumService hashes;

  public NewsRecoveryCatalog(JdbcTemplate db, LlmJson json, ChecksumService hashes) {
    this.db = db;
    this.json = json;
    this.hashes = hashes;
  }

  @Transactional
  public int seed() throws java.io.IOException {
    try (var stream = new ClassPathResource("llm/news_content_recovery.json").getInputStream()) {
      var resource = json.mapper().readTree(stream);
      String checksum = hashes.sha256(resource.toString());
      db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "recovery-prompt");
      var old =
          db.queryForList(
              "SELECT checksum FROM llm_prompt_templates WHERE task_code=? AND version=?",
              String.class,
              TASK,
              resource.path("version").asText());
      if (!old.isEmpty()) {
        if (!old.getFirst().equals(checksum))
          throw new IllegalStateException("Immutable recovery prompt changed; increment version");
        return 0;
      }
      db.update("UPDATE llm_prompt_templates SET enabled=false WHERE task_code=?", TASK);
      return db.update(
          "INSERT INTO"
              + " llm_prompt_templates(id,task_code,version,input_domain,description,system_prompt,request_schema,response_schema,checksum,enabled)"
              + " VALUES (?,?,?,'NEWS',?,?,?::jsonb,?::jsonb,?,true)",
          UUID.randomUUID(),
          TASK,
          resource.path("version").asText(),
          resource.path("description").asText(),
          resource.path("system_prompt").asText(),
          resource.path("request_schema").toString(),
          resource.path("response_schema").toString(),
          checksum);
    }
  }

  public LlmPromptCatalog.Template active() {
    return db
        .query(
            "SELECT * FROM llm_prompt_templates WHERE task_code=? AND enabled",
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
        .orElseThrow(() -> new IllegalStateException("RECOVERY_PROMPT_NOT_SEEDED"));
  }
}
