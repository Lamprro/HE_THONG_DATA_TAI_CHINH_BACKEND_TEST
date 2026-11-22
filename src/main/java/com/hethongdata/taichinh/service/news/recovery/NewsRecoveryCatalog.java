package com.hethongdata.taichinh.service.news.recovery;

import com.hethongdata.taichinh.repository.jdbc.llm.PromptTemplateJdbcRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.llm.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NewsRecoveryCatalog {
  public static final String TASK = "NEWS_CONTENT_RECOVERY";
  private final PromptTemplateJdbcRepository repository;
  private final LlmJson json;
  private final ChecksumService hashes;

  public NewsRecoveryCatalog(PromptTemplateJdbcRepository repository, LlmJson json, ChecksumService hashes) {
    this.repository = repository;
    this.json = json;
    this.hashes = hashes;
  }

  @Transactional
  public int seed() throws java.io.IOException {
    try (var stream = new ClassPathResource("llm/news_content_recovery.json").getInputStream()) {
      var resource = json.mapper().readTree(stream);
      String checksum = hashes.sha256(resource.toString());
      repository.lockPrompt(TASK);
      var old = repository.findChecksum(TASK, resource.path("version").asText());
      if (old.isPresent()) {
        if (!old.get().equals(checksum))
          throw new IllegalStateException("Immutable recovery prompt changed; increment version");
        return 0;
      }
      repository.disableAllTaskPrompts(TASK);
      return repository.insertActiveVersion(
          UUID.randomUUID(),
          TASK,
          resource.path("version").asText(),
          "NEWS",
          resource.path("description").asText(),
          resource.path("system_prompt").asText(),
          resource.path("request_schema").toString(),
          resource.path("response_schema").toString(),
          checksum);
    }
  }

  public LlmPromptCatalog.Template active() {
    var record = repository.findActiveByTask(TASK)
        .orElseThrow(() -> new IllegalStateException("RECOVERY_PROMPT_NOT_SEEDED"));
    return new LlmPromptCatalog.Template(
        record.id(),
        TASK,
        record.version(),
        record.systemPrompt(),
        json.read(record.requestSchema()),
        json.read(record.responseSchema()),
        record.checksum());
  }
}
