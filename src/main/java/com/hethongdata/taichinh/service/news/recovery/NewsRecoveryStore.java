package com.hethongdata.taichinh.service.news.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hethongdata.taichinh.dto.news.NewsRecoveryDtos.*;
import com.hethongdata.taichinh.repository.jdbc.news.NewsRecoveryJdbcRepository;
import com.hethongdata.taichinh.repository.jpa.news.NewsArticleCompanyJpaRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.llm.*;
import com.hethongdata.taichinh.service.news.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Short commit points only; provider calls/downloads never hold a database transaction. */
@Service
public class NewsRecoveryStore {
  private final NewsRecoveryJdbcRepository repo;
  private final LlmJson json;
  private final ChecksumService hashes;
  private final NewsRecoveryCatalog catalog;
  private final NewsCompanyMatcher matcher;
  private final NewsArticleCompanyJpaRepository links;

  public NewsRecoveryStore(
      NewsRecoveryJdbcRepository repo,
      LlmJson json,
      ChecksumService hashes,
      NewsRecoveryCatalog catalog,
      NewsCompanyMatcher matcher,
      NewsArticleCompanyJpaRepository links) {
    this.repo = repo;
    this.json = json;
    this.hashes = hashes;
    this.catalog = catalog;
    this.matcher = matcher;
    this.links = links;
  }

  public JsonNode article(UUID id) {
    return repo
        .findArticleJson(id)
        .map(json::read)
        .orElseThrow(() -> new IllegalArgumentException("Article not found"));
  }

  public String hash(JsonNode source) {
    return hashes.sha256(json.canonical(source).toString());
  }

  public List<Candidate> candidates(int limit) {
    return repo.findCandidates(limit);
  }

  @Transactional
  public Outcome claim(
      UUID article,
      LlmPromptCatalog.Template template,
      JsonNode source,
      String model,
      List<String> urls) {
    repo.lockArticleForUpdate(article);
    if (!hash(article(article)).equals(hash(source)))
      return new Outcome("SOURCE_CHANGED", null, article, List.of());
    if (!"UNIQUE".equals(source.path("dedup_status").asText())
        || source.path("is_deleted_source").asBoolean()
        || source.path("metadata").path("is_test").asBoolean()
        || source.path("metadata").path("llm_excluded").asBoolean()
        || source.path("url").asText().matches("(?i).*(real-db-|localhost|example\\.com|/test/).*"))
      return new Outcome("SKIPPED", null, article, List.of("SOURCE_NOT_ELIGIBLE"));
    repo.expireRunningLeases(article, NewsRecoveryCatalog.TASK);
    var pending = repo.findActiveRuns(article, NewsRecoveryCatalog.TASK);
    if (!pending.isEmpty())
      return new Outcome("EXISTING_RUN", pending.getFirst(), article, List.of());
    String fingerprint = validationFingerprint();
    String inputHash =
        hashes.sha256(hash(source) + template.checksum() + fingerprint + urls.toString());
    Integer failures = repo.countFailures(article, NewsRecoveryCatalog.TASK, inputHash);
    if (failures != null && failures >= 3)
      return new Outcome("RETRY_LIMIT", null, article, List.of());
    var metadata = json.mapper().createObjectNode();
    metadata.set("source", source);
    metadata
        .put("source_hash", hash(source))
        .put("template_checksum", template.checksum())
        .put("validation_policy", fingerprint);
    metadata.set("admin_document_urls", json.mapper().valueToTree(urls));
    UUID run = UUID.randomUUID();
    repo.insertRun(
        run,
        model,
        template.version(),
        inputHash,
        metadata.toString(),
        template.id(),
        NewsRecoveryCatalog.TASK,
        article);
    return new Outcome("CLAIMED", run, article, List.of());
  }

  public Run run(UUID id) {
    var rows = repo.findRunById(id, NewsRecoveryCatalog.TASK);
    if (rows.isEmpty()) throw new IllegalArgumentException("Recovery run not found");
    var r = rows.getFirst();
    return new Run(
        id,
        (UUID) r.get("news_article_id"),
        (String) r.get("status"),
        json.read(r.get("request_metadata").toString()),
        json.read(r.get("response_metadata").toString()),
        r.get("response_text") == null
            ? json.mapper().nullNode()
            : json.read(r.get("response_text").toString()),
        json.read(r.get("validation_errors").toString()));
  }

  public List<Run> pending(int limit) {
    return repo
        .findPendingRunIds(NewsRecoveryCatalog.TASK, limit)
        .stream()
        .map(this::run)
        .toList();
  }

  public void checkpoint(UUID run, JsonNode request, JsonNode metadata) {
    if (!repo.checkpoint(run, request.toString(), metadata.toString()))
      throw new IllegalStateException("RECOVERY_LEASE_LOST");
  }

  @Transactional
  public Outcome finish(
      UUID id,
      LlmPromptCatalog.Template template,
      LlmGateway.Reply reply,
      JsonNode output,
      JsonNode metadata,
      Set<String> retrieved) {
    repo.lockRunForUpdate(id);
    var run = run(id);
    if (!"RUNNING".equals(run.status()))
      return new Outcome("LEASE_LOST", id, run.articleId(), List.of());
    repo.lockArticleForShare(run.articleId());
    var evaluation =
        evaluate(
            id,
            run.articleId(),
            template,
            output,
            retrieved,
            hash(article(run.articleId()))
                .equals(run.requestMetadata().path("source_hash").asText()),
            currentPolicy(run, template));
    // Even a partial proposal remains reviewable. Hard validation failures block approval.
    ((ObjectNode) metadata)
        .put("review_state", "PENDING_ADMIN_REVIEW")
        .set("retrieved_urls", json.mapper().valueToTree(retrieved));
    repo.stageResponse(
        id,
        output.toString(),
        reply.rawBody(),
        metadata.toString(),
        json.mapper().valueToTree(evaluation.errors()).toString(),
        evaluation.round(),
        reply.httpStatus(),
        reply.selectedModel(),
        reply.inputTokens(),
        reply.outputTokens());
    repo.updateTotals(id);
    return new Outcome("PENDING_ADMIN_REVIEW", id, run.articleId(), evaluation.errors());
  }

  private boolean currentPolicy(Run run, LlmPromptCatalog.Template template) {
    return template.checksum().equals(run.requestMetadata().path("template_checksum").asText())
        && catalog.active().checksum().equals(template.checksum())
        && validationFingerprint()
            .equals(run.requestMetadata().path("validation_policy").asText());
  }

  public Outcome fail(UUID runId, UUID article, String reason) {
    repo.failRun(runId, reason);
    repo.updateTotals(runId);
    return new Outcome("FAILED", runId, article, List.of(reason));
  }

  @Transactional
  public Outcome reject(UUID id, ReviewRequest review) {
    repo.lockRunForUpdate(id);
    var run = run(id);
    if (!"PENDING_VALIDATION".equals(run.status())) throw conflict("RUN_NOT_AWAITING_REVIEW");
    var meta = (ObjectNode) run.responseMetadata().deepCopy();
    review(meta, review, "REJECTED");
    repo.rejectRun(id, meta.toString());
    return new Outcome("REJECTED", id, run.articleId(), List.of());
  }

  @Transactional
  public Outcome approve(UUID id, ReviewRequest review) {
    repo.lockRunForUpdate(id);
    var run = run(id);
    if (!"PENDING_VALIDATION".equals(run.status())) throw conflict("RUN_NOT_AWAITING_REVIEW");
    repo.lockArticleForUpdate(run.articleId());
    var current = article(run.articleId());
    if (!hash(current).equals(run.requestMetadata().path("source_hash").asText()))
      throw conflict("SOURCE_CHANGED_SINCE_RECOVERY");
    var template = catalog.active();
    if (!currentPolicy(run, template)) throw conflict("RECOVERY_POLICY_CHANGED");
    var retrieved = new HashSet<String>();
    run.responseMetadata().path("retrieved_urls").forEach(v -> retrieved.add(v.asText()));
    var output = run.proposal();
    var evaluation =
        evaluate(id, run.articleId(), template, output, retrieved, true, true);
    if (!evaluation.errors().isEmpty()) {
      repo.updateValidationErrors(id, json.mapper().valueToTree(evaluation.errors()).toString(), evaluation.round());
      return new Outcome("VALIDATION_BLOCKED", id, run.articleId(), evaluation.errors());
    }
    String content = output.path("proposed_content").asText(), contentHash = hashes.sha256(content);
    repo.lockContentHash(contentHash);
    if (repo.contentHashExists(contentHash, run.articleId()))
      throw conflict("DUPLICATE_CONTENT_REQUIRES_MERGE_REVIEW");
    String date =
        output.hasNonNull("published_at")
            ? output.path("published_at").asText()
            : current.hasNonNull("published_at") ? current.path("published_at").asText() : "";
    if (date.isBlank()) throw conflict("PUBLISHED_AT_REQUIRED");
    Instant published;
    try {
      published = OffsetDateTime.parse(date).toInstant();
    } catch (java.time.format.DateTimeParseException invalid) {
      throw conflict("PUBLISHED_AT_INVALID");
    }
    var articleMetadata = (ObjectNode) current.path("metadata").deepCopy();
    articleMetadata
        .put("content_status", "RECOVERED_ADMIN_APPROVED")
        .put("extraction_status", "SUCCESS")
        .put("extraction_method", "GEMINI_ADMIN_REVIEWED")
        .put("recovery_assessment", output.path("assessment").asText())
        .put("recovery_run_id", id.toString());
    articleMetadata.set("documents", run.responseMetadata().path("documents"));
    String sapo =
        current.hasNonNull("sapo") && content.contains(current.path("sapo").asText())
            ? current.path("sapo").asText()
            : null;
    try {
      repo.updateArticle(
          run.articleId(),
          output.path("proposed_title").asText(),
          sapo,
          content,
          contentHash,
          output.hasNonNull("author") ? output.path("author").asText() : null,
          java.sql.Timestamp.from(published),
          articleMetadata.toString());
    } catch (org.springframework.dao.DataIntegrityViolationException conflict) {
      if (String.valueOf(conflict.getMostSpecificCause().getMessage())
          .contains("ux_news_unique_content_hash"))
        throw conflict("DUPLICATE_CONTENT_REQUIRES_MERGE_REVIEW");
      throw conflict;
    }
    var draft =
        new NewsArticleDraft(
            current.path("url").asText(),
            output.path("proposed_title").asText(),
            sapo,
            content,
            null,
            published,
            null,
            contentHash,
            articleMetadata);
    links.deleteTextMatchesByArticleId(run.articleId());
    for (var match : matcher.match(draft, matcher.loadCatalog()))
      links.insertMatchIfAbsent(
          run.articleId(),
          match.companyId(),
          match.securityId(),
          match.score(),
          "TEXT_MATCH",
          match.evidence().toString());
    repo.retireCurrentResults(run.articleId());
    var meta = (ObjectNode) run.responseMetadata().deepCopy();
    review(meta, review, "APPROVED");
    meta.put("approved_content_hash", contentHash);
    meta.put("approved_source_hash", hash(article(run.articleId())));
    repo.approveRun(id, meta.toString(), evaluation.round());
    return new Outcome("APPROVED", id, run.articleId(), List.of());
  }

  private void review(ObjectNode meta, ReviewRequest review, String status) {
    meta.put("review_state", status)
        .put("reviewer_label", review.reviewer())
        .put("review_reason", review.reason())
        .put("reviewed_at", Instant.now().toString());
  }

  private ResponseStatusException conflict(String reason) {
    return new ResponseStatusException(HttpStatus.CONFLICT, reason);
  }

  // ---- Recovery validation: DB rule catalogue (NEWS_RECOVERY) and per-rule audit inside the publication transaction. ----
  public record Evaluation(UUID round, List<String> errors) {}

  private static final Set<String> REQUIRED_RULES =
      Set.of(
          "RECOVERY_SCHEMA",
          "RECOVERY_SOURCE",
          "RECOVERY_CONTENT",
          "RECOVERY_SNAPSHOT",
          "RECOVERY_POLICY");
  private List<JsonNode> validationRules() {
    var rawRules = repo.findValidationRulesJson();
    var rows = rawRules.stream().map(json::read).toList();
    var missing = new HashSet<>(REQUIRED_RULES);
    for (var r : rows) {
      if (!REQUIRED_RULES.contains(r.path("code").asText())
          || !r.path("code").equals(r.path("executor"))
          || !Set.of("ERROR", "CRITICAL").contains(r.path("severity").asText())
          || !"1".equals(r.path("config").path("version").asText()))
        throw new IllegalStateException("RECOVERY_RULE_CONFIGURATION_INVALID");
      missing.remove(r.path("code").asText());
    }
    if (!missing.isEmpty()) throw new IllegalStateException("RECOVERY_RULES_MISSING");
    return rows;
  }

  public String validationFingerprint() {
    return hashes.sha256(json.mapper().valueToTree(validationRules()).toString());
  }

  public static List<String> contentIssues(JsonNode output) {
    var issues = new ArrayList<String>();
    String body = output.path("proposed_content").asText();
    if (body.strip().length() < 200 || body.length() > 150000)
      issues.add("RECOVERY_BODY_MISSING_OR_OUT_OF_BOUNDS");
    if (output.path("proposed_title").asText().isBlank()) issues.add("RECOVERY_TITLE_MISSING");
    if (body.contains("Giá hiện tại Thay đổi Xem hồ sơ doanh nghiệp TIN MỚI"))
      issues.add("RECOVERY_PAGE_CHROME");
    if (output.path("evidence").isEmpty()) issues.add("RECOVERY_EVIDENCE_MISSING");
    for (var e : output.path("evidence"))
      if (e.path("quote").asText().strip().length() < 10
          || !body.contains(e.path("quote").asText())) issues.add("RECOVERY_QUOTE_MISMATCH");
    if (output.hasNonNull("published_at"))
      try {
        var date = OffsetDateTime.parse(output.path("published_at").asText());
        if (date.isAfter(OffsetDateTime.now().plusDays(1))) issues.add("RECOVERY_FUTURE_DATE");
      } catch (Exception e) {
        issues.add("RECOVERY_DATE_INVALID");
      }
    if (Set.of("UNREADABLE", "UNCERTAIN").contains(output.path("assessment").asText()))
      issues.add("RECOVERY_CONTENT_UNCERTAIN");
    return issues;
  }

  Evaluation evaluate(
      UUID run,
      UUID article,
      LlmPromptCatalog.Template template,
      JsonNode output,
      Set<String> retrieved,
      boolean snapshot,
      boolean policy) {
    UUID round = UUID.randomUUID();
    var all = new ArrayList<String>();
    for (var rule : validationRules()) {
      List<String> issues =
          switch (rule.path("code").asText()) {
            case "RECOVERY_SCHEMA" ->
                output == null
                    ? List.of("RECOVERY_RESPONSE_MISSING")
                    : LlmJson.validateSchema(template.responseSchema(), output);
            case "RECOVERY_SOURCE" -> {
              var e = new ArrayList<String>();
              if (output == null || !article.toString().equals(output.path("article_id").asText()))
                e.add("RECOVERY_ARTICLE_MISMATCH");
              if (retrieved.isEmpty()) e.add("RECOVERY_RETRIEVAL_NOT_CONFIRMED");
              if (output != null)
                for (var ref : output.path("evidence"))
                  if (!retrieved.contains(ref.path("url").asText()))
                    e.add("RECOVERY_EVIDENCE_SOURCE_NOT_RETRIEVED");
              yield e;
            }
            case "RECOVERY_CONTENT" ->
                output == null ? List.of("RECOVERY_RESPONSE_MISSING") : contentIssues(output);
            case "RECOVERY_SNAPSHOT" -> snapshot ? List.of() : List.of("RECOVERY_SOURCE_CHANGED");
            default -> policy ? List.of() : List.of("RECOVERY_POLICY_CHANGED");
          };
      all.addAll(issues);
      repo.insertValidationResult(
          UUID.randomUUID(),
          rule.path("id").asLong(),
          rule.path("code").asText(),
          rule.path("severity").asText(),
          issues.isEmpty() ? "PASS" : "FAIL",
          issues.isEmpty() ? "NOT_REQUIRED" : "OPEN",
          run,
          round,
          rule.toString(),
          json.mapper().valueToTree(issues).toString(),
          issues.isEmpty() ? "Passed; admin review still required" : String.join("; ", issues));
    }
    return new Evaluation(round, all.stream().distinct().toList());
  }
}
