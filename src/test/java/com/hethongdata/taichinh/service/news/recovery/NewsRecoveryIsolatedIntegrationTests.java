package com.hethongdata.taichinh.service.news.recovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.dto.news.NewsRecoveryDtos.*;
import com.hethongdata.taichinh.service.llm.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Synthetic provider responses ONLY in an isolated schema, never public business tables. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "financial.llm.catalog.seed-enabled=false", "financial.llm.scheduler.enabled=false",
      "financial.ingestion.scheduler.enabled=false", "financial.validation.scheduler.enabled=false",
      "financial.master-data.catalog.seed-enabled=false",
          "financial.validation.catalog.seed-enabled=false",
      "financial.ingestion.catalog.seed-enabled=false",
          "financial.documents.cloudinary.enabled=false",
      "financial.admin.api-token=recovery-integration-admin-token-not-a-real-secret"
    })
@EnabledIfEnvironmentVariable(named = "RECOVERY_TEST_DB", matches = "1")
class NewsRecoveryIsolatedIntegrationTests {
  static final String SCHEMA = "recovery_test_" + UUID.randomUUID().toString().replace("-", "");
  static final Properties CONFIG = new Properties();
  static String jdbcUrl;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    try (var reader = Files.newBufferedReader(Path.of("application-local.properties"))) {
      CONFIG.load(reader);
    }
    jdbcUrl = CONFIG.getProperty("spring.datasource.url");
    try (var c = connect();
        var s = c.createStatement()) {
      s.execute("CREATE SCHEMA " + SCHEMA);
      for (String table :
          List.of(
              "companies",
              "company_aliases",
              "securities",
              "news_articles",
              "news_article_companies",
              "llm_prompt_templates",
              "llm_runs",
              "llm_results",
              "validation_rules",
              "validation_results"))
        s.execute(
            "CREATE TABLE " + SCHEMA + "." + table + " (LIKE public." + table + " INCLUDING ALL)");
      s.execute("SET search_path TO " + SCHEMA);
      for (String table : List.of("companies", "company_aliases", "securities"))
        s.execute("INSERT INTO " + table + " SELECT * FROM public." + table);
      s.execute(
          "INSERT INTO news_articles SELECT * FROM public.news_articles WHERE dedup_status='UNIQUE'"
              + " AND length(content_text)>1000 AND canonical_url NOT LIKE '%real-db-%' ORDER BY"
              + " published_at DESC LIMIT 2");
      s.execute(
          "INSERT INTO news_article_companies SELECT * FROM public.news_article_companies WHERE"
              + " news_article_id IN (SELECT id FROM news_articles)");
      try (var in =
          new ClassPathResource("db/manual/V20261004_06__news_recovery.sql").getInputStream()) {
        s.execute(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      }
    }
    try (var c = connect(); var s = c.createStatement(); var in = new ClassPathResource("db/manual/V20261008_01__merge_llm_attempts_into_runs.sql").getInputStream()) {
      s.execute("SET search_path TO " + SCHEMA);
      s.execute(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    registry.add(
        "spring.datasource.url",
        () -> jdbcUrl + (jdbcUrl.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
    registry.add(
        "spring.datasource.username", () -> CONFIG.getProperty("spring.datasource.username"));
    registry.add(
        "spring.datasource.password", () -> CONFIG.getProperty("spring.datasource.password"));
  }

  static Connection connect() throws SQLException {
    return DriverManager.getConnection(
        jdbcUrl,
        CONFIG.getProperty("spring.datasource.username"),
        CONFIG.getProperty("spring.datasource.password"));
  }

  @AfterAll
  static void cleanup() throws Exception {
    if (!SCHEMA.matches("recovery_test_[a-f0-9]{32}"))
      throw new IllegalStateException("Unsafe schema");
    try (var c = connect();
        var s = c.createStatement()) {
      s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }
  }

  @Autowired NewsRecoveryStore store;
  @Autowired NewsRecoveryService service;
  @Autowired NewsRecoveryCatalog catalog;
  @Autowired JdbcTemplate db;
  @Autowired LlmJson json;
  @Autowired TestRestTemplate http;
  @MockitoBean LlmGateway gateway;
  UUID article;
  String body;
  AtomicInteger calls;

  @BeforeEach
  void setup() throws Exception {
    db.update("DELETE FROM validation_results");
    db.update("DELETE FROM llm_results");
    db.update("DELETE FROM llm_runs");
    db.update(
        "UPDATE news_articles a SET"
            + " title=p.title,content_text=p.content_text,content_hash=p.content_hash,metadata=p.metadata,updated_at=p.updated_at"
            + " FROM public.news_articles p WHERE a.id=p.id");
    article = db.queryForObject("SELECT id FROM news_articles ORDER BY id LIMIT 1", UUID.class);
    body = store.article(article).path("content_text").asText();
    catalog.seed();
    calls = new AtomicInteger();
    when(gateway.configured()).thenReturn(true);
    when(gateway.model()).thenReturn("isolated-test-provider");
    var builder = new GeminiLlmGateway(json, "", "gemini-isolated-test-provider", false);
    when(gateway.request(any(), any()))
        .thenAnswer(i -> builder.request(i.getArgument(0), i.getArgument(1)));
    doAnswer(
            i -> {
              calls.incrementAndGet();
              var source = store.article(article);
              var output = json.mapper().createObjectNode();
              output
                  .put("article_id", article.toString())
                  .put("assessment", "COMPLETE")
                  .put("proposed_title", source.path("title").asText())
                  .put("proposed_content", body)
                  .putNull("author")
                  .putNull("published_at")
                  .put("preliminary_overview", "Isolated response; never production data");
              output.putArray("attachments");
              output.putArray("limitations");
              output
                  .putArray("evidence")
                  .addObject()
                  .put("url", source.path("url").asText())
                  .put("quote", body.substring(0, Math.min(100, body.length())))
                  .putNull("page");
              var raw = json.mapper().createObjectNode();
              raw.putArray("candidates")
                  .addObject()
                  .putObject("urlContextMetadata")
                  .putArray("urlMetadata")
                  .addObject()
                  .put("retrievedUrl", source.path("url").asText())
                  .put("urlRetrievalStatus", "URL_RETRIEVAL_STATUS_SUCCESS");
              java.util.function.Consumer<LlmGateway.Attempt> audit = i.getArgument(1);
              audit.accept(
                  new LlmGateway.Attempt(
                      1,
                      "isolated-test-provider",
                      200,
                      null,
                      raw.toString(),
                      output.toString(),
                      1,
                      2,
                      3));
              return new LlmGateway.Reply(
                  200, raw.toString(), output.toString(), 1, 2, "isolated-test-provider");
            })
        .when(gateway)
        .call(any(), any());
  }

  HttpHeaders headers() {
    var h = new HttpHeaders();
    h.setBearerAuth("recovery-integration-admin-token-not-a-real-secret");
    return h;
  }

  ResponseEntity<JsonNode> post(String path, Object value) {
    return http.exchange(
        "/api/admin/news-recovery" + path,
        HttpMethod.POST,
        new HttpEntity<>(value, headers()),
        JsonNode.class);
  }

  UUID execute() {
    var response = post("/news/" + article + "/execute", Map.of());
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody().path("status").asText()).isEqualTo("PENDING_ADMIN_REVIEW");
    return UUID.fromString(response.getBody().path("runId").asText());
  }

  @Test
  void httpApprovalRequiresAdminAndPreservesSnapshotWithAudit() {
    assertThat(
            http.getForEntity("/api/admin/news-recovery/configuration", String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);
    db.update("UPDATE news_articles SET content_text=NULL,content_hash=NULL WHERE id=?", article);
    UUID run = execute();
    assertThat(store.article(article).path("content_text").isNull()).isTrue();
    var duplicate = post("/news/" + article + "/execute", Map.of());
    assertThat(duplicate.getBody().path("status").asText()).isEqualTo("EXISTING_RUN");
    assertThat(calls.get()).isEqualTo(1);
    var response =
        post(
            "/runs/" + run + "/approve",
            new ReviewRequest("test reviewer", "Inspected source in isolated test"));
    assertThat(response.getBody().path("status").asText()).isEqualTo("APPROVED");
    assertThat(store.article(article).path("content_text").asText()).isEqualTo(body);
    assertThat(store.run(run).requestMetadata().path("source").path("content_text").isNull())
        .isTrue();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM validation_results WHERE llm_run_id=?", Integer.class, run))
        .isEqualTo(10);
    assertThat(db.queryForObject("SELECT count(*) FROM llm_results", Integer.class)).isZero();
    assertThat(
            post("/runs/" + run + "/approve", new ReviewRequest("test", "double approval"))
                .getStatusCode()
                .value())
        .isEqualTo(409);
  }

  @Test
  void rejectPreservesArticleAndPreventsAnalysis() {
    var original = store.article(article);
    UUID run = execute();
    assertThat(post("/runs/" + run + "/analyze", Map.of()).getStatusCode().value()).isEqualTo(400);
    assertThat(
            post("/runs/" + run + "/reject", new ReviewRequest("test", "Not sufficient"))
                .getBody()
                .path("status")
                .asText())
        .isEqualTo("REJECTED");
    assertThat(store.article(article)).isEqualTo(original);
  }

  @Test
  void failureBeforeProviderCallKeepsUnknownTokensAndZeroLatency() {
    UUID run = execute();
    db.update("UPDATE llm_runs SET status='RUNNING',attempts='[]'::jsonb WHERE id=?", run);
    store.fail(run, article, "ISOLATED_TEST_BEFORE_PROVIDER");
    var row = db.queryForMap("SELECT input_tokens,output_tokens,latency_ms FROM llm_runs WHERE id=?", run);
    assertThat(row.get("input_tokens")).isNull();
    assertThat(row.get("output_tokens")).isNull();
    assertThat(row.get("latency_ms")).isEqualTo(0);
  }

  @Test
  void sourceChangeAndDuplicateContentBlockApproval() {
    UUID run = execute();
    db.update("UPDATE news_articles SET title=title||' changed' WHERE id=?", article);
    assertThat(
            post("/runs/" + run + "/approve", new ReviewRequest("test", "source changed"))
                .getStatusCode()
                .value())
        .isEqualTo(409);
    db.update(
        "UPDATE news_articles a SET title=p.title FROM public.news_articles p WHERE a.id=p.id");
    post("/runs/" + run + "/reject", new ReviewRequest("test", "stale proposal"));
    body =
        db.queryForObject(
            "SELECT content_text FROM news_articles WHERE id<>? LIMIT 1", String.class, article);
    run = execute();
    assertThat(
            post("/runs/" + run + "/approve", new ReviewRequest("test", "duplicate source"))
                .getStatusCode()
                .value())
        .isEqualTo(409);
  }

  @Test
  void missingRetrievalCannotBeApprovedAndConfigurationFailsClosed() throws Exception {
    doAnswer(
            i -> {
              var source = store.article(article);
              var output = json.mapper().createObjectNode();
              output
                  .put("article_id", article.toString())
                  .put("assessment", "UNREADABLE")
                  .put("proposed_title", source.path("title").asText())
                  .put("proposed_content", "")
                  .putNull("author")
                  .putNull("published_at")
                  .put("preliminary_overview", "");
              output.putArray("attachments");
              output.putArray("evidence");
              output.putArray("limitations").add("Cannot read source");
              return new LlmGateway.Reply(
                  200, "{}", output.toString(), 1, 1, "isolated-test-provider");
            })
        .when(gateway)
        .call(any(), any());
    UUID run = execute();
    assertThat(
            post("/runs/" + run + "/approve", new ReviewRequest("test", "must not publish empty"))
                .getBody()
                .path("status")
                .asText())
        .isEqualTo("VALIDATION_BLOCKED");
    assertThat(store.run(run).status()).isEqualTo("PENDING_VALIDATION");
    post("/runs/" + run + "/reject", new ReviewRequest("test", "no content"));
    db.update("UPDATE validation_rules SET is_active=false WHERE code='RECOVERY_SOURCE'");
    assertThatThrownBy(() -> service.execute(article, new ExecuteRequest(List.of())))
        .isInstanceOf(IllegalStateException.class);
    db.update("UPDATE validation_rules SET is_active=true WHERE code='RECOVERY_SOURCE'");
  }
}
