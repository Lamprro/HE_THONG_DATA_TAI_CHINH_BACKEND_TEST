package com.hethongdata.taichinh.service.news.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hethongdata.taichinh.dto.news.NewsRecoveryDtos.*;
import com.hethongdata.taichinh.repository.jpa.news.NewsArticleCompanyJpaRepository;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.llm.*;
import com.hethongdata.taichinh.service.news.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Short commit points only; provider calls/downloads never hold a database transaction. */
@Service
public class NewsRecoveryStore {
  private final JdbcTemplate db;
  private final LlmJson json;
  private final ChecksumService hashes;
  private final NewsRecoveryCatalog catalog;
  private final NewsRecoveryValidation validation;
  private final NewsCompanyMatcher matcher;
  private final NewsArticleCompanyJpaRepository links;

  public NewsRecoveryStore(
      JdbcTemplate db,
      LlmJson json,
      ChecksumService hashes,
      NewsRecoveryCatalog catalog,
      NewsRecoveryValidation validation,
      NewsCompanyMatcher matcher,
      NewsArticleCompanyJpaRepository links) {
    this.db = db;
    this.json = json;
    this.hashes = hashes;
    this.catalog = catalog;
    this.validation = validation;
    this.matcher = matcher;
    this.links = links;
  }

  public JsonNode article(UUID id) {
    return db
        .query(
            "SELECT"
                + " jsonb_build_object('id',id,'url',canonical_url,'title',title,'sapo',sapo,'content_text',content_text,'content_hash',content_hash,'author',author,'published_at',published_at,'updated_at',updated_at,'dedup_status',dedup_status,'is_deleted_source',is_deleted_source,'metadata',metadata)::text"
                + " FROM news_articles WHERE id=?",
            (r, n) -> json.read(r.getString(1)),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Article not found"));
  }

  public String hash(JsonNode source) {
    return hashes.sha256(json.canonical(source).toString());
  }

  public List<Candidate> candidates(int limit) {
    return db.query(
        """
SELECT id,title,canonical_url,coalesce(length(content_text),0) FROM news_articles a
WHERE dedup_status='UNIQUE' AND NOT is_deleted_source AND coalesce(length(content_text),0)<200
AND NOT coalesce((metadata->>'is_test')::boolean,false) AND NOT coalesce((metadata->>'llm_excluded')::boolean,false)
AND canonical_url !~* '(real-db-|localhost|example.com|/test/)'
AND NOT EXISTS (SELECT 1 FROM llm_runs r WHERE r.news_article_id=a.id AND r.task_code='NEWS_CONTENT_RECOVERY'
  AND (r.status='PENDING_VALIDATION' OR r.status='RUNNING' AND r.created_at>now()-interval '15 minutes'
    OR r.status IN ('FAILED','REJECTED') AND r.created_at>now()-interval '1 hour'))
AND (SELECT count(*) FROM llm_runs r WHERE r.news_article_id=a.id AND r.task_code='NEWS_CONTENT_RECOVERY'
  AND r.status IN ('FAILED','REJECTED') AND r.created_at>=a.updated_at)<3
ORDER BY published_at DESC NULLS LAST,id LIMIT ?
""",
        (r, n) ->
            new Candidate(r.getObject(1, UUID.class), r.getString(2), r.getString(3), r.getInt(4)),
        Math.max(1, Math.min(50, limit)));
  }

  @Transactional
  public Outcome claim(
      UUID article,
      LlmPromptCatalog.Template template,
      JsonNode source,
      String model,
      List<String> urls) {
    db.queryForList("SELECT id FROM news_articles WHERE id=? FOR UPDATE", article);
    if (!hash(article(article)).equals(hash(source)))
      return new Outcome("SOURCE_CHANGED", null, article, List.of());
    if (!"UNIQUE".equals(source.path("dedup_status").asText())
        || source.path("is_deleted_source").asBoolean()
        || source.path("metadata").path("is_test").asBoolean()
        || source.path("metadata").path("llm_excluded").asBoolean()
        || source.path("url").asText().matches("(?i).*(real-db-|localhost|example\\.com|/test/).*"))
      return new Outcome("SKIPPED", null, article, List.of("SOURCE_NOT_ELIGIBLE"));
    db.update(
        "UPDATE llm_runs SET"
            + " status='FAILED',error_message='RECOVERY_LEASE_EXPIRED',finished_at=now() WHERE"
            + " news_article_id=? AND task_code=? AND status='RUNNING' AND"
            + " created_at<now()-interval '15 minutes'",
        article,
        NewsRecoveryCatalog.TASK);
    var pending =
        db.queryForList(
            "SELECT id FROM llm_runs WHERE news_article_id=? AND task_code=? AND status IN"
                + " ('RUNNING','PENDING_VALIDATION')",
            UUID.class,
            article,
            NewsRecoveryCatalog.TASK);
    if (!pending.isEmpty())
      return new Outcome("EXISTING_RUN", pending.getFirst(), article, List.of());
    String fingerprint = validation.fingerprint();
    String inputHash =
        hashes.sha256(hash(source) + template.checksum() + fingerprint + urls.toString());
    Integer failures =
        db.queryForObject(
            "SELECT count(*) FROM llm_runs WHERE news_article_id=? AND task_code=? AND input_hash=?"
                + " AND status IN ('FAILED','REJECTED')",
            Integer.class,
            article,
            NewsRecoveryCatalog.TASK,
            inputHash);
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
    db.update(
        "INSERT INTO"
            + " llm_runs(id,operation_type,provider,model_name,prompt_version,status,input_hash,request_metadata,response_metadata,created_at,prompt_template_id,task_code,news_article_id)"
            + " VALUES (?,'NEWS_PARSE','GEMINI',?,?,'RUNNING',?,?::jsonb,'{}'::jsonb,now(),?,?,?)",
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
    return db
        .query(
            "SELECT * FROM llm_runs WHERE id=? AND task_code=?",
            (r, n) ->
                new Run(
                    id,
                    r.getObject("news_article_id", UUID.class),
                    r.getString("status"),
                    json.read(r.getString("request_metadata")),
                    json.read(r.getString("response_metadata")),
                    r.getString("response_text") == null
                        ? json.mapper().nullNode()
                        : json.read(r.getString("response_text")),
                    json.read(r.getString("validation_errors"))),
            id,
            NewsRecoveryCatalog.TASK)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Recovery run not found"));
  }

  public List<Run> pending(int limit) {
    return db
        .queryForList(
            "SELECT id FROM llm_runs WHERE task_code=? AND status='PENDING_VALIDATION' ORDER BY"
                + " created_at,id LIMIT ?",
            UUID.class,
            NewsRecoveryCatalog.TASK,
            Math.max(1, Math.min(50, limit)))
        .stream()
        .map(this::run)
        .toList();
  }

  public void checkpoint(UUID run, JsonNode request, JsonNode metadata) {
    if (db.update(
            "UPDATE llm_runs SET request_payload=?::jsonb,response_metadata=?::jsonb WHERE id=? AND"
                + " status='RUNNING'",
            request.toString(),
            metadata.toString(),
            run)
        != 1) throw new IllegalStateException("RECOVERY_LEASE_LOST");
  }

  @Transactional
  public Outcome finish(
      UUID id,
      LlmPromptCatalog.Template template,
      LlmGateway.Reply reply,
      JsonNode output,
      JsonNode metadata,
      Set<String> retrieved) {
    db.queryForList("SELECT id FROM llm_runs WHERE id=? FOR UPDATE", id);
    var run = run(id);
    if (!"RUNNING".equals(run.status()))
      return new Outcome("LEASE_LOST", id, run.articleId(), List.of());
    db.queryForList("SELECT id FROM news_articles WHERE id=? FOR SHARE", run.articleId());
    var evaluation =
        validation.evaluate(
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
    db.update(
        "UPDATE llm_runs SET"
            + " status='PENDING_VALIDATION',response_text=?,response_payload=?::jsonb,response_metadata=?::jsonb,validation_errors=?::jsonb,validation_round_id=?,http_status=?,model_name=?,input_tokens=?,output_tokens=?"
            + " WHERE id=?",
        output.toString(),
        reply.rawBody(),
        metadata.toString(),
        json.mapper().valueToTree(evaluation.errors()).toString(),
        evaluation.round(),
        reply.httpStatus(),
        reply.selectedModel(),
        reply.inputTokens(),
        reply.outputTokens(),
        id);
    totals(id);
    return new Outcome("PENDING_ADMIN_REVIEW", id, run.articleId(), evaluation.errors());
  }

  private boolean currentPolicy(Run run, LlmPromptCatalog.Template template) {
    return template.checksum().equals(run.requestMetadata().path("template_checksum").asText())
        && catalog.active().checksum().equals(template.checksum())
        && validation
            .fingerprint()
            .equals(run.requestMetadata().path("validation_policy").asText());
  }

  public Outcome fail(UUID runId, UUID article, String reason) {
    db.update(
        "UPDATE llm_runs SET status='FAILED',error_message=?,finished_at=now() WHERE id=? AND"
            + " status='RUNNING'",
        reason,
        runId);
    totals(runId);
    return new Outcome("FAILED", runId, article, List.of(reason));
  }

  private void totals(UUID id) {
    db.update("""
        UPDATE llm_runs SET
          input_tokens=(SELECT CASE WHEN count(a->>'input_tokens')=0 THEN NULL
                        ELSE least(sum((a->>'input_tokens')::bigint),2147483647) END FROM jsonb_array_elements(attempts) a),
          output_tokens=(SELECT CASE WHEN count(a->>'output_tokens')=0 THEN NULL
                         ELSE least(sum((a->>'output_tokens')::bigint),2147483647) END FROM jsonb_array_elements(attempts) a),
          latency_ms=(SELECT least(coalesce(sum((a->>'latency_ms')::bigint),0),2147483647) FROM jsonb_array_elements(attempts) a)
        WHERE id=?
        """, id);
  }

  @Transactional
  public Outcome reject(UUID id, ReviewRequest review) {
    db.queryForList("SELECT id FROM llm_runs WHERE id=? FOR UPDATE", id);
    var run = run(id);
    if (!"PENDING_VALIDATION".equals(run.status())) throw conflict("RUN_NOT_AWAITING_REVIEW");
    var meta = (ObjectNode) run.responseMetadata().deepCopy();
    review(meta, review, "REJECTED");
    db.update(
        "UPDATE llm_runs SET"
            + " status='REJECTED',response_metadata=?::jsonb,finished_at=now(),error_message='ADMIN_REJECTED'"
            + " WHERE id=?",
        meta.toString(),
        id);
    return new Outcome("REJECTED", id, run.articleId(), List.of());
  }

  @Transactional
  public Outcome approve(UUID id, ReviewRequest review) {
    db.queryForList("SELECT id FROM llm_runs WHERE id=? FOR UPDATE", id);
    var run = run(id);
    if (!"PENDING_VALIDATION".equals(run.status())) throw conflict("RUN_NOT_AWAITING_REVIEW");
    db.queryForList("SELECT id FROM news_articles WHERE id=? FOR UPDATE", run.articleId());
    var current = article(run.articleId());
    if (!hash(current).equals(run.requestMetadata().path("source_hash").asText()))
      throw conflict("SOURCE_CHANGED_SINCE_RECOVERY");
    var template = catalog.active();
    if (!currentPolicy(run, template)) throw conflict("RECOVERY_POLICY_CHANGED");
    var retrieved = new HashSet<String>();
    run.responseMetadata().path("retrieved_urls").forEach(v -> retrieved.add(v.asText()));
    var output = run.proposal();
    var evaluation =
        validation.evaluate(id, run.articleId(), template, output, retrieved, true, true);
    if (!evaluation.errors().isEmpty()) {
      db.update(
          "UPDATE llm_runs SET validation_errors=?::jsonb,validation_round_id=? WHERE id=?",
          json.mapper().valueToTree(evaluation.errors()).toString(),
          evaluation.round(),
          id);
      return new Outcome("VALIDATION_BLOCKED", id, run.articleId(), evaluation.errors());
    }
    String content = output.path("proposed_content").asText(), contentHash = hashes.sha256(content);
    db.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", "news-content:" + contentHash);
    if (Boolean.TRUE.equals(
        db.queryForObject(
            "SELECT EXISTS(SELECT 1 FROM news_articles WHERE content_hash=? AND id<>?)",
            Boolean.class,
            contentHash,
            run.articleId()))) throw conflict("DUPLICATE_CONTENT_REQUIRES_MERGE_REVIEW");
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
      db.update(
          "UPDATE news_articles SET"
              + " title=?,sapo=?,content_text=?,content_hash=?,author=coalesce(?,author),published_at=?,metadata=?::jsonb,updated_at=now()"
              + " WHERE id=?",
          output.path("proposed_title").asText(),
          sapo,
          content,
          contentHash,
          output.hasNonNull("author") ? output.path("author").asText() : null,
          java.sql.Timestamp.from(published),
          articleMetadata.toString(),
          run.articleId());
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
    db.update(
        "UPDATE llm_results SET is_current=false WHERE news_article_id=? AND is_current",
        run.articleId());
    var meta = (ObjectNode) run.responseMetadata().deepCopy();
    review(meta, review, "APPROVED");
    meta.put("approved_content_hash", contentHash);
    meta.put("approved_source_hash", hash(article(run.articleId())));
    db.update(
        "UPDATE llm_runs SET"
            + " status='SUCCESS',response_metadata=?::jsonb,validation_round_id=?,validation_errors='[]'::jsonb,finished_at=now()"
            + " WHERE id=?",
        meta.toString(),
        evaluation.round(),
        id);
    return new Outcome("APPROVED", id, run.articleId(), List.of());
  }

  private void review(ObjectNode meta, ReviewRequest review, String status) {
    meta.put("review_state", status)
        .put("reviewer_label", review.reviewer())
        .put("review_reason", review.reason())
        .put("reviewed_at", Instant.now().toString());
    // A shared admin credential authenticates access. Label is attribution, NOT a verified account
    // ID.
  }

  private ResponseStatusException conflict(String reason) {
    return new ResponseStatusException(HttpStatus.CONFLICT, reason);
  }
}
