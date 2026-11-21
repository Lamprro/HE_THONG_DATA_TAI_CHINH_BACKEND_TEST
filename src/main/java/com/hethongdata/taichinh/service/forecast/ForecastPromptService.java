package com.hethongdata.taichinh.service.forecast;

import com.hethongdata.taichinh.repository.jdbc.llm.PromptTemplateJdbcRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.llm.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ForecastPromptService {
  public static final String TASK = "FINANCIAL_SCENARIOS";
  private final PromptTemplateJdbcRepository repository;
  private final LlmJson json;
  private final ChecksumService hashes;

  public ForecastPromptService(PromptTemplateJdbcRepository repository, LlmJson json, ChecksumService hashes) {
    this.repository = repository;
    this.json = json;
    this.hashes = hashes;
  }

  @Transactional
  public int seed() {
    try (var stream = new ClassPathResource("llm/financial_scenarios.json").getInputStream()) {
      var t = json.mapper().readTree(stream);
      String sum = hashes.sha256(t.toString());
      repository.lockPrompt(TASK);
      var existing = repository.findChecksum(TASK, t.path("version").asText());
      if (existing.isPresent()) {
        if (!existing.get().equals(sum))
          throw new IllegalStateException("Immutable forecast template changed: increase version");
        return 0;
      }
      repository.disableTaskPrompts(TASK);
      return repository.insertActiveVersion(
          UUID.randomUUID(),
          TASK,
          t.path("version").asText(),
          "FINANCIAL",
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
    var record = repository.findActiveByTaskAndDomain(TASK, "FINANCIAL")
        .orElseThrow(() -> new IllegalArgumentException("Forecast template not seeded or disabled"));
    return new LlmPromptCatalog.Template(
        record.id(),
        TASK,
        record.version(),
        record.systemPrompt(),
        json.read(record.requestSchema()),
        json.read(record.responseSchema()),
        record.checksum());
  }

  @Transactional
  public com.hethongdata.taichinh.dto.forecast.ForecastDtos.TemplateState enabled(
      UUID id, boolean enabled) {
    repository.lockPrompt(TASK);
    if (!repository.existsByIdAndTask(id, TASK)) {
      throw new IllegalArgumentException("Forecast template not found");
    }
    if (enabled) {
      repository.disableTaskPrompts(TASK);
    }
    repository.setEnabled(id, enabled);
    return new com.hethongdata.taichinh.dto.forecast.ForecastDtos.TemplateState(id, enabled);
  }

  public List<com.hethongdata.taichinh.dto.forecast.ForecastDtos.TemplateSummary> list() {
    return repository.listSummariesByTask(TASK).stream()
        .map(r -> new com.hethongdata.taichinh.dto.forecast.ForecastDtos.TemplateSummary(
            r.id(), r.version(), r.enabled(), r.checksum(), r.description()))
        .toList();
  }
}
