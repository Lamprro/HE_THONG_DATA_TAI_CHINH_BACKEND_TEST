package com.hethongdata.taichinh.controller.admin;

import com.hethongdata.taichinh.dto.news.NewsRecoveryDtos.*;
import com.hethongdata.taichinh.service.llm.*;
import com.hethongdata.taichinh.service.news.recovery.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/news-recovery")
public class NewsRecoveryAdminController {
  private final NewsRecoveryService service;
  private final NewsRecoveryStore store;
  private final NewsRecoveryCatalog catalog;
  private final NewsLlmService analysis;

  public NewsRecoveryAdminController(
      NewsRecoveryService service,
      NewsRecoveryStore store,
      NewsRecoveryCatalog catalog,
      NewsLlmService analysis) {
    this.service = service;
    this.store = store;
    this.catalog = catalog;
    this.analysis = analysis;
  }

  @GetMapping("/configuration")
  public Configuration configuration() {
    return service.configuration();
  }

  @PostMapping("/template/seed")
  public Map<String, Integer> seed() throws java.io.IOException {
    return Map.of("inserted", catalog.seed());
  }

  @GetMapping("/candidates")
  public List<Candidate> candidates(@RequestParam(defaultValue = "20") int limit) {
    return store.candidates(limit);
  }

  @PostMapping("/news/{articleId}/execute")
  public Outcome execute(@PathVariable UUID articleId, @Valid @RequestBody ExecuteRequest request) {
    return service.execute(articleId, request);
  }

  @GetMapping("/pending")
  public List<Run> pending(@RequestParam(defaultValue = "20") int limit) {
    return store.pending(limit);
  }

  @GetMapping("/runs/{runId}")
  public Run run(@PathVariable UUID runId) {
    return store.run(runId);
  }

  @PostMapping("/runs/{runId}/approve")
  public Outcome approve(@PathVariable UUID runId, @Valid @RequestBody ReviewRequest request) {
    return store.approve(runId, request);
  }

  @PostMapping("/runs/{runId}/reject")
  public Outcome reject(@PathVariable UUID runId, @Valid @RequestBody ReviewRequest request) {
    return store.reject(runId, request);
  }

  @PostMapping("/runs/{runId}/analyze")
  public Map<String, LlmRunStore.Outcome> analyze(@PathVariable UUID runId) {
    var run = store.run(runId);
    if (!"SUCCESS".equals(run.status())
        || !"APPROVED".equals(run.responseMetadata().path("review_state").asText()))
      throw new IllegalArgumentException("RECOVERY_NOT_APPROVED");
    if (!store
        .hash(store.article(run.articleId()))
        .equals(run.responseMetadata().path("approved_source_hash").asText()))
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.CONFLICT, "ARTICLE_CHANGED_AFTER_APPROVAL");
    return analysis.executeAll(run.articleId());
  }
}
