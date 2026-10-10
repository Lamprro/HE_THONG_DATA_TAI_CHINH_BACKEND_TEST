package com.hethongdata.taichinh.service.news.recovery;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.service.ingestion.ChecksumService;
import com.hethongdata.taichinh.service.llm.*;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class NewsRecoveryValidation {
  public record Evaluation(UUID round, List<String> errors) {}

  private static final Set<String> REQUIRED =
      Set.of(
          "RECOVERY_SCHEMA",
          "RECOVERY_SOURCE",
          "RECOVERY_CONTENT",
          "RECOVERY_SNAPSHOT",
          "RECOVERY_POLICY");
  private final JdbcTemplate db;
  private final LlmJson json;
  private final ChecksumService hashes;

  public NewsRecoveryValidation(JdbcTemplate db, LlmJson json, ChecksumService hashes) {
    this.db = db;
    this.json = json;
    this.hashes = hashes;
  }

  private List<JsonNode> rules() {
    var rows =
        db.query(
            "SELECT"
                + " jsonb_build_object('id',id,'code',code,'executor',executor_key,'severity',severity,'config',rule_config)::text"
                + " FROM validation_rules WHERE data_domain='NEWS_RECOVERY' AND is_active ORDER BY"
                + " code",
            (r, n) -> json.read(r.getString(1)));
    var missing = new HashSet<>(REQUIRED);
    for (var r : rows) {
      if (!REQUIRED.contains(r.path("code").asText())
          || !r.path("code").equals(r.path("executor"))
          || !Set.of("ERROR", "CRITICAL").contains(r.path("severity").asText())
          || !"1".equals(r.path("config").path("version").asText()))
        throw new IllegalStateException("RECOVERY_RULE_CONFIGURATION_INVALID");
      missing.remove(r.path("code").asText());
    }
    if (!missing.isEmpty()) throw new IllegalStateException("RECOVERY_RULES_MISSING");
    return rows;
  }

  public String fingerprint() {
    return hashes.sha256(json.mapper().valueToTree(rules()).toString());
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

  public Evaluation evaluate(
      UUID run,
      UUID article,
      LlmPromptCatalog.Template template,
      JsonNode output,
      Set<String> retrieved,
      boolean snapshot,
      boolean policy) {
    UUID round = UUID.randomUUID();
    var all = new ArrayList<String>();
    for (var rule : rules()) {
      List<String> issues =
          switch (rule.path("code").asText()) {
            case "RECOVERY_SCHEMA" ->
                output == null
                    ? List.of("RECOVERY_RESPONSE_MISSING")
                    : LlmSchemaValidator.validate(template.responseSchema(), output);
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
      db.update(
          "INSERT INTO"
              + " validation_results(id,validation_rule_id,rule_code,severity,result_status,handling_status,checked_at,validation_target,llm_run_id,validation_round_id,rule_snapshot,observed_value,expected_value,message)"
              + " VALUES (?,?,?,?,?,?,now(),'LLM_OUTPUT',?,?,?::jsonb,?,'PASS',?)",
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
