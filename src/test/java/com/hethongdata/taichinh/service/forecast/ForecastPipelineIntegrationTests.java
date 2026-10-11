package com.hethongdata.taichinh.service.forecast;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.dto.forecast.*;
import com.hethongdata.taichinh.service.llm.*;
import java.nio.file.*;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Real DB copies, isolated schema. Synthetic model responses never reach public tables. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "financial.admin.api-token=isolated-test-admin-credential-not-production",
      "financial.llm.catalog.seed-enabled=false",
      "financial.llm.scheduler.enabled=false",
      "financial.ingestion.scheduler.enabled=false",
      "financial.validation.scheduler.enabled=false"
    })
@EnabledIfEnvironmentVariable(named = "LLM_TEST_DB", matches = "1")
class ForecastPipelineIntegrationTests {
  static final String SCHEMA = "forecast_test_" + UUID.randomUUID().toString().replace("-", "");
  static final Properties CONFIG = new Properties();
  static String url;

  static Connection connection() throws Exception {
    return DriverManager.getConnection(
        url,
        CONFIG.getProperty("spring.datasource.username"),
        CONFIG.getProperty("spring.datasource.password"));
  }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) throws Exception {
    try (var reader =
        Files.newBufferedReader(
            Path.of(
                System.getenv()
                    .getOrDefault("LLM_TEST_CONFIG", "../application-local.properties")))) {
      CONFIG.load(reader);
    }
    url = CONFIG.getProperty("spring.datasource.url");
    try (var c = connection();
        var s = c.createStatement()) {
      s.execute("CREATE SCHEMA " + SCHEMA);
      s.execute("SET search_path TO " + SCHEMA);
      for (String t :
          List.of(
              "data_sources",
              "ingestion_jobs",
              "ingestion_runs",
              "news_articles",
              "news_article_companies",
              "raw_payloads",
              "data_versions",
              "companies",
              "company_aliases",
              "securities",
              "financial_periods",
              "financial_statements",
              "financial_statement_items",
              "financial_metrics",
              "metric_definitions",
              "market_prices",
              "macro_series",
              "macro_observations",
              "llm_runs",
              "llm_prompt_templates",
              "llm_results",
              "validation_rules",
              "validation_results"))
        s.execute("CREATE TABLE " + t + " (LIKE public." + t + " INCLUDING ALL)");
      // LIKE renames copied indexes. Let migrations create their own indexes before testing legacy fixtures.
      s.execute("DROP INDEX IF EXISTS llm_results_security_id_task_code_as_of_date_idx");
      s.execute("DROP INDEX IF EXISTS llm_results_security_id_task_code_forecast_period_start_for_idx");
      for (String t :
          List.of(
              "data_sources",
              "raw_payloads",
              "data_versions",
              "companies",
              "company_aliases",
              "securities",
              "financial_periods",
              "financial_statements",
              "financial_statement_items",
              "financial_metrics",
              "metric_definitions",
              "validation_rules",
              "market_prices")) s.execute("INSERT INTO " + t + " SELECT * FROM public." + t);
      com.hethongdata.taichinh.support.IsolatedSchemaSupport.detachSequences(c,SCHEMA);
      s.execute("CREATE SEQUENCE test_metric_ids START 1000000");
      s.execute(
          "ALTER TABLE metric_definitions ALTER COLUMN id SET DEFAULT nextval('test_metric_ids')");
      s.execute("CREATE SEQUENCE test_rule_ids START 1000000");
      s.execute(
          "ALTER TABLE validation_rules ALTER COLUMN id SET DEFAULT nextval('test_rule_ids')");
      try (var stream =
          new ClassPathResource("db/manual/V20261004_05__financial_forecasts.sql")
              .getInputStream()) {
        s.execute(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      }
      try (var stream = new ClassPathResource("db/manual/V20261011_01__forecast_result_periods.sql").getInputStream()) {
        s.execute(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      }
      try (var stream = new ClassPathResource("db/manual/V20261012_01__financial_metadata.sql").getInputStream()) {
        s.execute(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      }
      try (var stream = new ClassPathResource("db/manual/V20261004_04__news_validation_catalog.sql").getInputStream()) {
        s.execute(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
      }
    }
    try (var c = connection();
        var s = c.createStatement();
        var in =
            new ClassPathResource("db/manual/V20261008_01__merge_llm_attempts_into_runs.sql")
                .getInputStream()) {
      s.execute("SET search_path TO " + SCHEMA);
      s.execute(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
    }
    registry.add(
        "spring.datasource.url",
        () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + SCHEMA);
    registry.add(
        "spring.datasource.username", () -> CONFIG.getProperty("spring.datasource.username"));
    registry.add(
        "spring.datasource.password", () -> CONFIG.getProperty("spring.datasource.password"));
  }

  @AfterAll
  static void cleanup() throws Exception {
    if (!SCHEMA.matches("forecast_test_[a-f0-9]{32}"))
      throw new IllegalStateException("Unsafe test schema");
    try (var c = connection();
        var s = c.createStatement()) {
      s.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }
  }

  @Autowired JdbcTemplate db;
  @Autowired LlmJson json;
  @Autowired ForecastPromptService prompts;
  @Autowired ForecastContextService contexts;
  @Autowired FinancialForecastService service;
  @Autowired ForecastRunStore store;
  @Autowired TestRestTemplate http;
  @MockitoBean LlmGateway gateway;
  @MockitoBean com.hethongdata.taichinh.application.port.ExternalFinancialDataPort external;
  @Autowired com.hethongdata.taichinh.service.news.NewsWorkflowService news;
  @Autowired com.hethongdata.taichinh.service.financial.FinancialStatementBuildService financialBuild;
  @Autowired com.hethongdata.taichinh.service.ingestion.IngestionService ingestion;
  @Autowired com.hethongdata.taichinh.service.validation.ValidationJobService rawValidation;
  @Autowired com.hethongdata.taichinh.repository.jpa.ingestion.RawPayloadJpaRepository rawRepo;
  @Autowired com.hethongdata.taichinh.repository.jpa.ingestion.IngestionRunJpaRepository ingestionRunRepo;
  @Autowired com.hethongdata.taichinh.repository.jpa.ingestion.IngestionJobJpaRepository jobRepo;
  @Autowired com.hethongdata.taichinh.repository.jpa.ingestion.DataSourceJpaRepository sourceRepo;
  @Autowired ForecastValidationService validations;
  @Autowired jakarta.persistence.EntityManager entityManager;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  ForecastRequest request;

  void withRollback(Runnable action) {
    new org.springframework.transaction.support.TransactionTemplate(transactions)
        .executeWithoutResult(
            status -> {
              try {
                action.run();
              } finally {
                status.setRollbackOnly();
              }
            });
  }

  @BeforeEach
  void setup() throws Exception {
    db.update("DELETE FROM validation_results");
    db.update("DELETE FROM llm_results");
    db.update("DELETE FROM llm_runs");
    db.update("DELETE FROM financial_metrics WHERE calculation_key IS NOT NULL");
    db.update(
        "UPDATE validation_rules SET"
            + " is_active=true,severity='ERROR',rule_config=jsonb_set(rule_config,'{version}','\"1\"') WHERE data_domain='LLM_FORECAST'");
    prompts.seed();
    db.update("UPDATE llm_prompt_templates SET enabled=true");
    request =
        new ForecastRequest(
            db.queryForObject("SELECT id FROM securities WHERE symbol='FPT'", UUID.class),
            LocalDate.of(2026, 10, 4),
            1,
            EnumSet.of(ForecastRequest.Target.TOTAL_ASSETS, ForecastRequest.Target.OWNERS_EQUITY),
            false);
    reset(gateway);
    when(gateway.configured()).thenReturn(true);
    when(gateway.model()).thenReturn("test-only-model");
    when(gateway.routingKey()).thenReturn("test-routing");
    var builder = new GeminiLlmGateway(json, "", "gemini-test-model", false);
    when(gateway.request(any(), any()))
        .thenAnswer(i -> builder.request(i.getArgument(0), i.getArgument(1)));
    when(gateway.call(any(), any()))
        .thenAnswer(
            i -> {
              JsonNode p = i.getArgument(0);
              return new LlmGateway.Reply(
                  200,
                  "{\"isolated_fixture\":true}",
                  output(p).toString(),
                  1,
                  1,
                  "test-only-model");
            });
  }

  JsonNode output(JsonNode payload) {
    var input =
        json.read(payload.path("contents").path(0).path("parts").path(0).path("text").asText());
    var o = json.mapper().createObjectNode();
    o.put("schema_version", "financial.forecast.output.v2")
        .put("task_code", "FINANCIAL_SCENARIOS")
        .put("company_id", input.path("company_id").asText())
        .put("security_id", input.path("security_id").asText())
        .put("as_of_date", input.path("as_of_date").asText())
        .put("forecast_period_end", input.path("forecast_period_end").asText())
        .put("status", "PARTIAL")
        .put("overview", "Isolated test scenario, not production financial guidance")
        .put("macro_used", false);
    o.putArray("risks").add("Not calibrated");
    o.putArray("limitations").add("Macro absent; unknown reporting scope and period basis.");
    var rows = o.putArray("forecasts");
    for (var target : input.path("targets")) {
      var history = new ArrayList<JsonNode>();
      for (var p : input.path("points"))
        if (ForecastRequest.Target.valueOf(target.asText())
            .matches(p.path("domain").asText(), p.path("code").asText())) history.add(p);
      history.sort(Comparator.comparing(p -> p.path("periodEnd").asText()));
      var latest = history.getLast();
      var f =
          rows.addObject()
              .put("metric_code", target.asText())
              .put("base_point_id", latest.path("id").asText());
      int growth = 0;
      for (String name : List.of("bear", "base", "bull")) {
        var s =
            f.putObject(name)
                .put("growth_percent", growth)
                .put("rationale", "Scenario assumption based on supplied history");
        s.putArray("evidence_ids")
            .add(latest.path("id").asText())
            .add(history.getFirst().path("id").asText());
        growth += 5;
      }
    }
    return o;
  }

  @Test
  void fullFlowPublishesSevenAuditRulesCalculatesValuesAndCaches() throws Exception {
    var preview = service.preview(request);
    assertThat(preview.eligible()).isTrue();
    assertThat(preview.coverage().macroObservations()).isZero();
    var outcome = service.execute(request);
    assertThat(outcome.status()).isEqualTo("SUCCESS");
    assertThat(store.results(request.securityId(), 10))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.data().path("calculated_values").size()).isEqualTo(2);
              assertThat(r.qualityStatus()).isEqualTo("WARNING");
            });
    assertThat(store.run(outcome.runId()).validations()).hasSize(7);
    assertThat(service.execute(request).status()).isEqualTo("CACHED");
    verify(gateway, times(1)).call(any(), any());
  }

  @Test void readinessEndpointUsesTheIsolatedDatabase() {
    var response = http.getForEntity("/api/health", JsonNode.class);
    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().path("database").path("status").asText()).isEqualTo("UP");
    assertThat(http.getForEntity("/api/health/live", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
    verifyNoInteractions(gateway);
  }

  @Test void legacyWriterCanOmitPeriodColumnsAfterMigration() {
    var first = service.execute(request);
    var later = new ForecastRequest(request.securityId(),request.asOfDate().plusDays(1),1,request.targets(),false);
    var context = contexts.build(later);
    var template = prompts.active();
    var claim = store.claim(context,template,"legacy-writer-contract",gateway.request(template,context.input()),
        gateway.model(),validations.fingerprint(),gateway.routingKey());
    assertThat(claim.status()).isEqualTo("CLAIMED");
    UUID id = UUID.randomUUID();
    db.update("""
        INSERT INTO llm_results(id,llm_run_id,prompt_template_id,task_code,source_domain,company_id,
          security_id,as_of_date,source_hash,input_hash,schema_version,overview,result_json,quality_status,
          validation_round_id,validation_policy_hash)
        SELECT ?,?,prompt_template_id,task_code,source_domain,company_id,security_id,
          as_of_date+1,source_hash,input_hash,schema_version,overview,result_json,quality_status,
          validation_round_id,validation_policy_hash FROM llm_results WHERE id=?
        """,id,claim.runId(),first.resultId());
    var row = db.queryForMap("SELECT forecast_period_start,forecast_period_end,target_scope,is_current FROM llm_results WHERE id=?",id);
    assertThat(row.get("forecast_period_end").toString()).isEqualTo("2027-03-31");
    assertThat(row.get("target_scope")).isEqualTo(request.targetScope());
    assertThat(row.get("is_current")).isEqualTo(true);
    assertThat(db.queryForObject("SELECT is_current FROM llm_results WHERE id=?",Boolean.class,first.resultId())).isFalse();
  }

  @Test
  void urlOnlyFlowsThroughRawValidationAndArticleBuildAndCanUpgradeWithoutDuplicate() {
    withRollback(() -> {
      db.update("DELETE FROM data_versions WHERE data_domain IN ('NEWS','NEWS_DATA')");
      var source=sourceRepo.findById(db.queryForObject("SELECT id FROM data_sources WHERE provider='cafef' LIMIT 1",Long.class)).orElseThrow();
      var fetchJob=jobRepo.save(com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity.create(
          source,"NEWS_DATA_FETCH","Isolated NEWS fetch","NEWS_DATA",null,json.mapper().createObjectNode(),(short)10,60,false));
      var buildJob=jobRepo.save(com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity.create(
          source,"NEWS_ARTICLE_BUILD","Isolated NEWS build","NEWS_DATA",null,json.mapper().createObjectNode(),(short)10,60,false));
      var listingJob=jobRepo.save(com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity.create(
          source,"NEWS_LIST_FIXTURE","Isolated NEWS listing","NEWS",null,json.mapper().createObjectNode(),(short)10,60,false));
      var sourceRaw=fixtureRaw(source,listingJob,"NEWS",json.read("""
          {"provider":"cafef","dataset":"news","count":1,"data":[{"symbol":"FPT",
          "url":"https://cafef.vn/isolated-metadata-flow.chn","title":"FPT link","publishedAt":"01/10/2026 09:00"}]}
          """));
      assertThat(rawValidation.validate(sourceRaw.getId()).getDataVersionId()).isNotNull();
      when(external.fetch(any())).thenThrow(new com.hethongdata.taichinh.application.port.error.ExternalFetchException(
          com.hethongdata.taichinh.application.port.error.ExternalErrorCategory.TRANSPORT,null,"Isolated endpoint failure"));
      assertThat(news.execute(fetchJob,"MANUAL").getStatus()).isEqualTo("RETRY_PENDING");
      entityManager.flush();
      UUID fetched=db.queryForObject("SELECT id FROM raw_payloads WHERE entity_type='NEWS_DATA' AND payload->>'requested_url'='https://cafef.vn/isolated-metadata-flow.chn' ORDER BY fetched_at DESC LIMIT 1",UUID.class);
      assertThat(rawValidation.validate(fetched).getDataVersionId()).isNotNull();
      news.execute(buildJob,"MANUAL");
      entityManager.flush();
      UUID article=db.queryForObject("SELECT id FROM news_articles WHERE canonical_url='https://cafef.vn/isolated-metadata-flow.chn'",UUID.class);
      assertThat(db.queryForObject("SELECT content_text FROM news_articles WHERE id=?",String.class,article)).isNull();
      assertThat(db.queryForObject("SELECT count(*) FROM news_article_companies WHERE news_article_id=?",Integer.class,article)).isEqualTo(1);
      assertThat(new NewsLlmContext(db,json,new com.hethongdata.taichinh.service.ingestion.ChecksumService())
          .build(article,"NEWS_SUMMARY").issues()).contains("BODY_MISSING_OR_TOO_SHORT");
      assertThat(db.queryForObject("SELECT count(*) FROM validation_results WHERE raw_payload_id=? AND result_status='FAIL' AND severity='ERROR'",Integer.class,fetched)).isZero();
      reset(external);
      var body="Clean publisher content about FPT business results. ".repeat(8);
      var envelope=json.mapper().createObjectNode().put("final_url","https://cafef.vn/isolated-metadata-flow.chn")
          .put("http_status",200).put("content_type","text/html").put("textual",true)
          .put("body","<html><body><p>"+body+"</p></body></html>")
          .put("extraction_status","SUCCESS").put("canonical_url","https://cafef.vn/isolated-metadata-flow.chn")
          .put("title","FPT article").put("content_text",body).put("published_at","2026-10-01T09:00:00+07:00");
      when(external.fetch(any())).thenReturn(new com.hethongdata.taichinh.application.port.model.ExternalFetchResponse(
          com.hethongdata.taichinh.application.port.model.ExternalOperation.FETCH_URL,"cafef",
          java.net.URI.create("https://python.test/api/v1/url-fetch"),200,"application/json",Map.of(),envelope.toString(),Instant.now()));
      news.execute(fetchJob,"MANUAL");
      entityManager.flush();
      UUID upgraded=db.queryForObject("SELECT id FROM raw_payloads WHERE entity_type='NEWS_DATA' AND payload->>'requested_url'='https://cafef.vn/isolated-metadata-flow.chn' AND payload->>'extraction_status'='SUCCESS'",UUID.class);
      rawValidation.validate(upgraded);
      news.execute(buildJob,"MANUAL");
      entityManager.flush();
      assertThat(db.queryForObject("SELECT count(*) FROM news_articles WHERE canonical_url='https://cafef.vn/isolated-metadata-flow.chn'",Integer.class)).isEqualTo(1);
      assertThat(db.queryForObject("SELECT content_text FROM news_articles WHERE id=?",String.class,article)).isEqualTo(body.trim());
    });
  }

  private com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity fixtureRaw(
      com.hethongdata.taichinh.entity.ingestion.DataSourceEntity source,
      com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity job,String type,JsonNode payload) {
    if (payload.isObject() && !payload.has("retrieved_at"))
      ((com.fasterxml.jackson.databind.node.ObjectNode)payload).put("retrieved_at",Instant.now().toString());
    var run=com.hethongdata.taichinh.entity.ingestion.IngestionRunEntity.start(source,job,"MANUAL",
        json.mapper().createObjectNode(),java.net.URI.create("https://python.test/fixture"),json.mapper().createObjectNode(),Instant.now());
    run.markBatchSuccess(json.mapper().createObjectNode(),Instant.now(),1);
    run=ingestionRunRepo.saveAndFlush(run);
    return rawRepo.saveAndFlush(com.hethongdata.taichinh.entity.ingestion.RawPayloadEntity.create(
        run,source,UUID.randomUUID().toString(),type,"FPT","https://python.test/fixture","application/json",payload,
        null,"a".repeat(64),Instant.now(),request.securityId()));
  }

  @Test
  void financialMetadataFlowsThroughValidationAndBuildWithoutChangingSourceValues() {
    withRollback(() -> {
      db.update("DELETE FROM data_versions WHERE data_domain='FINANCIAL_STATEMENT' AND status='ACTIVE'");
      var source=sourceRepo.findById(db.queryForObject("SELECT id FROM data_sources WHERE provider='vndirect' LIMIT 1",Long.class)).orElseThrow();
      var job=jobRepo.save(com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity.create(
          source,"FINANCIAL_STATEMENT_BUILD","Isolated financial metadata","FINANCIAL_STATEMENT",null,json.mapper().createObjectNode(),(short)10,60,false));
      var raw=fixtureRaw(source,job,"FINANCIAL_STATEMENT",json.read("""
          {"provider":"vndirect","symbol":"FPT","dataset":"balance_sheet","data":[
           {"itemCode":"TOTAL_ASSETS","itemName":"Total assets","numericValue":12,"fiscalDate":"2026-06-30"}],
           "financial_metadata":{"schema_version":"financial.metadata.v1","provider":"vndirect",
           "report_scope":"CONSOLIDATED","period_basis":"POINT_IN_TIME","currency":"VND","unit_scale":1000000,
           "published_at":"2026-07-28T09:00:00+07:00","evidence":{
           "report_scope":[{"field":"reportScope","value":"Hợp nhất","normalized":"CONSOLIDATED"}],
           "period_basis":[{"field":"dataset","value":"balance_sheet","normalized":"POINT_IN_TIME"}],
           "currency":[{"field":"unit","value":"triệu đồng","normalized":"VND"}],
           "unit_scale":[{"field":"unit","value":"triệu đồng","normalized":1000000}],
           "published_at":[{"field":"publishedAt","value":"2026-07-28T09:00:00+07:00","normalized":"2026-07-28T09:00:00+07:00"}]}}}
          """));
      assertThat(rawValidation.validate(raw.getId()).getDataVersionId()).isNotNull();
      assertThat(financialBuild.execute(job,"MANUAL").getStatus()).isEqualTo("SUCCESS");
      var row=db.queryForMap("SELECT s.report_scope,s.period_basis,s.currency,s.unit_scale,s.published_at,s.metadata_provenance::text,i.value FROM financial_statements s JOIN financial_statement_items i ON i.financial_statement_id=s.id WHERE s.raw_payload_id=?",raw.getId());
      assertThat(row.get("report_scope")).isEqualTo("CONSOLIDATED");
      assertThat(row.get("period_basis")).isEqualTo("POINT_IN_TIME");
      assertThat(row.get("currency")).isEqualTo("VND");
      assertThat(((Number)row.get("unit_scale")).longValue()).isEqualTo(1000000);
      assertThat((java.math.BigDecimal)row.get("value")).isEqualByComparingTo("12");
      assertThat(row.get("metadata_provenance").toString()).contains("triệu đồng");
      var ctx=contexts.build(new ForecastRequest(request.securityId(),LocalDate.now(),1,request.targets(),false));
      assertThat(ctx.points().stream().filter(p->p.scope().equals("CONSOLIDATED") && p.code().equals("TOTAL_ASSETS")).findFirst().orElseThrow().value()).isEqualByComparingTo("12000000");
    });
  }

  @Test
  @EnabledIfEnvironmentVariable(named="LIVE_P06",matches="1")
  void realDeployedPythonMetadataPassesTheExistingFinancialPipeline() {
    var properties=new com.hethongdata.taichinh.integration.python.PythonFinancialDataProperties();
    var factory=new org.springframework.http.client.SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(properties.getConnectTimeout());factory.setReadTimeout(properties.getReadTimeout());
    var adapter=new com.hethongdata.taichinh.integration.python.PythonExternalFinancialDataAdapter(
        org.springframework.web.client.RestClient.builder().requestFactory(factory).build(),properties,
        new com.hethongdata.taichinh.integration.python.SensitiveHeaderSanitizer());
    // Forward the production port to the real adapter. No synthetic response is returned here.
    when(external.resolveUri(any())).thenAnswer(i->adapter.resolveUri(i.getArgument(0)));
    when(external.fetch(any())).thenAnswer(i->adapter.fetch(i.getArgument(0)));
    withRollback(() -> {
      db.update("DELETE FROM data_versions WHERE data_domain='FINANCIAL_STATEMENT' AND status='ACTIVE'");
      var source=sourceRepo.findById(db.queryForObject("SELECT id FROM data_sources WHERE provider='vndirect' LIMIT 1",Long.class)).orElseThrow();
      var job=jobRepo.save(com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity.create(
          source,"FINANCIAL_STATEMENT_BUILD","Real Python metadata check","FINANCIAL_STATEMENT",null,json.mapper().createObjectNode(),(short)10,60,false));
      var parameters=json.mapper().createObjectNode().put("operation","FINANCIAL_STATEMENT")
          .put("provider","vndirect").put("symbol","FPT").put("securityId",request.securityId().toString());
      parameters.putObject("parameters").put("statement","balance-sheet").put("fiscal_date","2026-06-30");
      var fetchJob=jobRepo.save(com.hethongdata.taichinh.entity.ingestion.IngestionJobEntity.create(
          source,"ISOLATED_FPT_FINANCIAL_FETCH","Real FPT metadata fetch","FINANCIAL_STATEMENT",null,parameters,(short)10,60,false));
      var fetched=ingestion.ingestJob(fetchJob,"MANUAL");
      assertThat(fetched.getStatus()).isEqualTo("SUCCESS");
      var raw=rawRepo.findById(fetched.getRawPayloadId()).orElseThrow();
      var payload=raw.getPayload();
      assertThat(raw.getSourceUrl()).startsWith(properties.getBaseUrl().toString());
      assertThat(payload.path("financial_metadata").path("schema_version").asText()).isEqualTo("financial.metadata.v1");
      assertThat(payload.path("financial_metadata").path("report_scope").asText()).isEqualTo("UNKNOWN");
      assertThat(payload.path("financial_metadata").path("published_at").isNull()).isTrue();
      assertThat(rawValidation.validate(raw.getId()).getDataVersionId()).isNotNull();
      assertThat(financialBuild.execute(job,"MANUAL").getStatus()).isEqualTo("SUCCESS");
      var row=db.queryForMap("SELECT report_scope,period_basis,currency,unit_scale,published_at,metadata_provenance::text FROM financial_statements WHERE raw_payload_id=?",raw.getId());
      assertThat(row.get("report_scope")).isEqualTo("UNKNOWN");
      assertThat(row.get("period_basis")).isEqualTo("POINT_IN_TIME");
      assertThat(row.get("currency")).isNull();assertThat(row.get("unit_scale")).isNull();
      assertThat(row.get("published_at")).isNull();
      assertThat(row.get("metadata_provenance").toString()).contains("UNVERIFIED_CURRENCY");
      assertThat(db.queryForObject("SELECT count(*) FROM financial_statement_items i JOIN financial_statements s ON s.id=i.financial_statement_id WHERE s.raw_payload_id=?",Integer.class,raw.getId()))
          .isEqualTo(payload.path("data").size());
    });
  }

  @Test
  void macroCoverageNamesStaleAndMissingSeriesWithoutInventingObservations() {
    db.update("INSERT INTO macro_series SELECT * FROM public.macro_series");
    db.update("INSERT INTO macro_observations SELECT * FROM public.macro_observations");
    try {
      var macroRequest=new ForecastRequest(request.securityId(),LocalDate.of(2026,10,10),1,request.targets(),false);
      var preview=service.preview(macroRequest);
      assertThat(preview.input().path("macro_coverage").isArray()).isTrue();
      var lending=preview.coverage().macroSeries().stream().filter(s->s.code().equals("VN_LENDING_RATE_YEAR")).findFirst().orElseThrow();
      assertThat(lending.latestDate()).isEqualTo(LocalDate.of(2023,12,31));
      assertThat(lending.stale()).isTrue();assertThat(lending.sentObservationCount()).isZero();
      assertThat(preview.coverage().limitations()).anyMatch(s->s.contains("MACRO_STALE:VN_LENDING_RATE_YEAR"));
      assertThat(preview.input().path("points")).noneMatch(p->p.path("code").asText().equals("VN_LENDING_RATE_YEAR"));
      String hash=preview.snapshotHash();
      db.update("DELETE FROM macro_observations WHERE macro_series_id=(SELECT id FROM macro_series WHERE code='VN_LENDING_RATE_YEAR')");
      var missing=service.preview(macroRequest);
      assertThat(missing.snapshotHash()).isNotEqualTo(hash);
      assertThat(missing.coverage().macroSeries().stream().filter(s->s.code().equals("VN_LENDING_RATE_YEAR")).findFirst().orElseThrow().status()).isEqualTo("MISSING");
    } finally {db.update("DELETE FROM macro_observations");db.update("DELETE FROM macro_series");}
  }

  @Test
  void missingMacroRequiredStopsBeforeCall() throws Exception {
    var required =
        new ForecastRequest(request.securityId(), request.asOfDate(), 1, request.targets(), true);
    assertThat(service.execute(required).status()).isEqualTo("SKIPPED");
    verify(gateway, never()).call(any(), any());
  }

  @Test
  void availableMacroWithoutCitedEvidenceRejectsClaimOfUsage() throws Exception {
    db.update("INSERT INTO macro_series SELECT * FROM public.macro_series");
    db.update("INSERT INTO macro_observations SELECT * FROM public.macro_observations");
    try {
      var withMacro =
          new ForecastRequest(
              request.securityId(), LocalDate.of(2026, 10, 10), 1, request.targets(), true);
      assertThat(service.preview(withMacro).coverage().macroObservations()).isPositive();
      doAnswer(
              i -> {
                var o = (com.fasterxml.jackson.databind.node.ObjectNode) output(i.getArgument(0));
                o.put("macro_used", true);
                return new LlmGateway.Reply(200, "{}", o.toString(), 1, 1);
              })
          .when(gateway)
          .call(any(), any());
      var outcome = service.execute(withMacro);
      assertThat(outcome.status()).isEqualTo("REJECTED");
      assertThat(outcome.issues()).contains("MACRO_FLAG_EVIDENCE_MISMATCH");
      assertThat(store.results(request.securityId(), 10)).isEmpty();
    } finally {
      db.update("DELETE FROM macro_observations");
      db.update("DELETE FROM macro_series");
    }
  }

  @Test
  void citedMacroEvidenceWithUsageFlagPublishesAndCaches() throws Exception {
    db.update("INSERT INTO macro_series SELECT * FROM public.macro_series");
    db.update("INSERT INTO macro_observations SELECT * FROM public.macro_observations");
    try {
      var withMacro =
          new ForecastRequest(
              request.securityId(), LocalDate.of(2026, 10, 10), 1, request.targets(), true);
      String macroId =
          contexts.build(withMacro).points().stream()
              .filter(p -> p.domain().equals("MACRO"))
              .findFirst()
              .orElseThrow()
              .id();
      doAnswer(
              i -> {
                var o = (com.fasterxml.jackson.databind.node.ObjectNode) output(i.getArgument(0));
                o.put("macro_used", true);
                for (var f : o.path("forecasts")) {
                  for (String scenario : List.of("bear", "base", "bull")) {
                    ((com.fasterxml.jackson.databind.node.ArrayNode)
                            f.path(scenario).path("evidence_ids"))
                        .add(macroId);
                  }
                }
                return new LlmGateway.Reply(200, "{}", o.toString(), 1, 1);
              })
          .when(gateway)
          .call(any(), any());
      var outcome = service.execute(withMacro);
      assertThat(outcome.status()).isEqualTo("SUCCESS");
      assertThat(store.run(outcome.runId()).validations())
          .hasSize(7)
          .allSatisfy(v -> assertThat(v.path("result_status").asText()).isEqualTo("PASS"));
      assertThat(service.execute(withMacro).status()).isEqualTo("CACHED");
      verify(gateway, times(1)).call(any(), any());
    } finally {
      db.update("DELETE FROM macro_observations");
      db.update("DELETE FROM macro_series");
    }
  }

  @Test
  void pretaxTargetUsesIncomeStatementNotDuplicateCashFlowItem() {
    var all =
        new ForecastRequest(
            request.securityId(),
            request.asOfDate(),
            1,
            EnumSet.allOf(ForecastRequest.Target.class),
            false);
    var ctx = contexts.build(all);
    assertThat(ctx.eligible()).isTrue();
    assertThat(ctx.points().stream().filter(p -> p.code().equals("PRETAX_PROFIT")).toList())
        .isNotEmpty()
        .allSatisfy(p -> assertThat(p.domain()).isEqualTo("INCOME_STATEMENT"));
  }

  @Test
  void missingRuleStopsBeforeCall() throws Exception {
    db.update("UPDATE validation_rules SET is_active=false WHERE code='FORECAST_EVIDENCE'");
    assertThat(service.execute(request).status()).isEqualTo("VALIDATION_CONFIGURATION_ERROR");
    verify(gateway, never()).call(any(), any());
  }

  @Test
  void invalidEvidenceRejectsWithoutResult() throws Exception {
    doAnswer(
            i -> {
              var o = output(i.getArgument(0));
              ((com.fasterxml.jackson.databind.node.ArrayNode)
                      o.path("forecasts").path(0).path("base").path("evidence_ids"))
                  .add("MISSING:POINT");
              return new LlmGateway.Reply(200, "{}", o.toString(), 1, 1);
            })
        .when(gateway)
        .call(any(), any());
    assertThat(service.execute(request).status()).isEqualTo("REJECTED");
    assertThat(store.results(request.securityId(), 10)).isEmpty();
  }

  @Test
  void schemaErrorAndRetryCapAreEnforced() throws Exception {
    doReturn(new LlmGateway.Reply(200, "{}", "{}", 1, 1)).when(gateway).call(any(), any());
    for (int i = 0; i < 3; i++) assertThat(service.execute(request).status()).isEqualTo("REJECTED");
    assertThat(service.execute(request).status()).isEqualTo("RETRY_LIMIT");
    verify(gateway, times(3)).call(any(), any());
  }

  @Test
  void ratioRecalculationIsIdempotentAndDoesNotStorePredictionsAsActuals() {
    var first = contexts.recalculate(request);
    assertThat(first.inserted()).isPositive();
    assertThat(contexts.recalculate(request).inserted()).isZero();
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM financial_metrics WHERE calculation_key IS NOT NULL AND (NOT"
                    + " is_derived OR is_canonical)",
                Integer.class))
        .isZero();
  }

  @Test
  void adminAuthenticationAndDtosAreEnforced() {
    var noAuth = http.postForEntity("/api/admin/forecasts/preview", request, String.class);
    assertThat(noAuth.getStatusCode().value()).isEqualTo(403);
    var headers = new HttpHeaders();
    headers.setBearerAuth("isolated-test-admin-credential-not-production");
    var response =
        http.exchange(
            "/api/admin/forecasts/preview",
            HttpMethod.POST,
            new HttpEntity<>(request, headers),
            JsonNode.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody().path("eligible").asBoolean()).isTrue();
    var invalid =
        http.exchange(
            "/api/admin/forecasts/preview",
            HttpMethod.POST,
            new HttpEntity<>(
                new ForecastRequest(
                    request.securityId(), request.asOfDate(), 99, request.targets(), false),
                headers),
            JsonNode.class);
    assertThat(invalid.getStatusCode().value()).isEqualTo(400);
  }

  @Test
  void browserPreflightIsAllowedButActualRequestStillNeedsAdminCredential() {
    var headers = new HttpHeaders();
    headers.setOrigin("http://localhost:5173");
    headers.setAccessControlRequestMethod(HttpMethod.POST);
    headers.setAccessControlRequestHeaders(List.of("authorization", "content-type"));
    var preflight =
        http.exchange(
            "/api/admin/forecasts/preview",
            HttpMethod.OPTIONS,
            new HttpEntity<>(headers),
            String.class);
    assertThat(preflight.getStatusCode().value()).isEqualTo(200);
    assertThat(
            http.postForEntity("/api/admin/forecasts/preview", request, String.class)
                .getStatusCode()
                .value())
        .isEqualTo(403);
  }

  @Test
  void pendingRecoveryDoesNotCallProvider() throws Exception {
    var ctx = contexts.build(request);
    var t = prompts.active();
    var payload = gateway.request(t, ctx.input());
    var claim =
        store.claim(
            ctx,
            t,
            "a".repeat(64),
            payload,
            "test-only-model",
            validations.fingerprint(),
            "test-routing");
    assertThat(
            store.stage(
                claim.runId(),
                new LlmGateway.Reply(200, "{}", output(payload).toString(), 1, 1),
                20))
        .isTrue();
    assertThat(service.pending(5))
        .singleElement()
        .satisfies(r -> assertThat(r.status()).isEqualTo("SUCCESS"));
    assertThat(service.pending(5)).isEmpty();
    verify(gateway, never()).call(any(), any());
  }

  @Test
  void changedRulesHideResultsUntilRevalidated() {
    var run = service.execute(request);
    assertThat(run.status()).isEqualTo("SUCCESS");
    db.update(
        "UPDATE validation_rules SET rule_config=jsonb_set(rule_config,'{version}','\"2\"') WHERE"
            + " code='FORECAST_SCHEMA'");
    assertThat(store.results(request.securityId(), 10)).isEmpty();
    assertThat(store.validate(run.runId()).status()).isEqualTo("SUCCESS");
    assertThat(store.results(request.securityId(), 10)).hasSize(1);
    assertThat(store.run(run.runId()).validations()).hasSize(14);
    assertThat(service.execute(request).status()).isEqualTo("CACHED");
  }

  @Test
  void futureCutoffIsRejected() {
    assertThatThrownBy(
            () ->
                service.preview(
                    new ForecastRequest(
                        request.securityId(),
                        LocalDate.now().plusYears(1),
                        1,
                        request.targets(),
                        false)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  ForecastRequest priceRequest() {
    return new ForecastRequest(
        request.securityId(),
        LocalDate.of(2026, 10, 9),
        1,
        EnumSet.of(ForecastRequest.Target.STOCK_PRICE),
        false);
  }

  @Test
  void priceFlowUsesVerifiedVndClosesJavaIndicatorsAndThreeCalculatedPrices() {
    var price = priceRequest();
    var preview = service.preview(price);
    assertThat(preview.eligible()).as(preview.issues().toString()).isTrue();
    assertThat(preview.forecastPeriodEnd()).isEqualTo("2027-03-31");
    assertThat(preview.input().path("derived_metrics").toString())
        .contains("SMA_20", "SMA_60", "PRICE_RETURN_20_SESSIONS");
    var run = service.execute(price);
    assertThat(run.status()).isEqualTo("SUCCESS");
    var result = store.results(price.securityId(), 10).getFirst().data();
    var calc = result.path("calculated_values").get(0);
    assertThat(calc.path("metric_code").asText()).isEqualTo("STOCK_PRICE");
    assertThat(calc.path("unit").asText()).isEqualTo("VND_PER_SHARE");
    assertThat(calc.path("base").decimalValue())
        .isEqualByComparingTo(
            calc.path("base_value").decimalValue().multiply(new java.math.BigDecimal("1.05")));
    assertThat(service.execute(price).status()).isEqualTo("CACHED");
    assertThat(store.run(run.runId()).validations()).hasSize(7);
  }

  @Test
  void priceCannotUseStoredNumbersWithoutMatchingRawEvidence() {
    db.update(
        "UPDATE market_prices SET close_price=close_price+1 WHERE id=(SELECT id FROM market_prices"
            + " WHERE security_id=? AND is_canonical AND interval_code='1d' ORDER BY"
            + " price_timestamp DESC LIMIT 1)",
        request.securityId());
    try {
      var preview = service.preview(priceRequest());
      assertThat(preview.eligible()).isFalse();
      assertThat(preview.issues()).contains("PRICE_UNIT_OR_RAW_PROVENANCE_UNVERIFIED");
      assertThat(service.execute(priceRequest()).status()).isEqualTo("SKIPPED");
      verify(gateway, never()).call(any(), any());
    } catch (Exception e) {
      throw new RuntimeException(e);
    } finally {
      db.update(
          "UPDATE market_prices SET close_price=close_price-1 WHERE id=(SELECT id FROM"
              + " market_prices WHERE security_id=? AND is_canonical AND interval_code='1d' ORDER"
              + " BY price_timestamp DESC LIMIT 1)",
          request.securityId());
    }
  }

  @Test
  void zeroOrNegativePriceScenarioIsRejectedWithoutPublishing() throws Exception {
    doAnswer(
            i -> {
              var response = output(i.getArgument(0));
              ((com.fasterxml.jackson.databind.node.ObjectNode)
                      response.path("forecasts").get(0).path("bear"))
                  .put("growth_percent", -100);
              return new LlmGateway.Reply(200, "{}", response.toString(), 1, 1);
            })
        .when(gateway)
        .call(any(), any());
    assertThat(service.execute(priceRequest()).status()).isEqualTo("REJECTED");
    assertThat(store.results(request.securityId(), 10)).isEmpty();
  }

  @Test
  void apiAcceptsSixTargetsAndPublishesPriceAlongsideFinancials() {
    var headers = new HttpHeaders();
    headers.setBearerAuth("isolated-test-admin-credential-not-production");
    var all =
        new ForecastRequest(
            request.securityId(),
            LocalDate.of(2026, 10, 9),
            1,
            EnumSet.allOf(ForecastRequest.Target.class),
            false);
    var response =
        http.exchange(
            "/api/admin/forecasts/execute",
            HttpMethod.POST,
            new HttpEntity<>(all, headers),
            JsonNode.class);
    assertThat(response.getStatusCode().value()).isEqualTo(200);
    assertThat(response.getBody().path("status").asText()).isEqualTo("SUCCESS");
    var result = store.results(request.securityId(), 10).getFirst().data();
    assertThat(result.path("forecasts")).hasSize(6);
    assertThat(result.path("calculated_values")).hasSize(6);
    assertThat(result.path("calculated_values").toString())
        .contains("STOCK_PRICE", "VND_PER_SHARE");
  }

  @Test
  void priceReceivedAfterCutoffCannotBecomeTheBase() {
    withRollback(
        () -> {
          long latest =
              db.queryForObject(
                  "SELECT id FROM market_prices WHERE security_id=? AND is_canonical AND"
                      + " interval_code='1d' ORDER BY price_timestamp DESC LIMIT 1",
                  Long.class,
                  request.securityId());
          db.update(
              "UPDATE market_prices SET created_at='2026-10-11T00:00:00+07:00' WHERE id=?", latest);
          var ctx = contexts.build(priceRequest());
          assertThat(ctx.eligible()).as(ctx.issues().toString()).isTrue();
          assertThat(ctx.points()).noneMatch(p -> p.id().equals("PRICE:" + latest));
        });
  }

  @Test
  void FinancialReportPublishedAfterCutoffIsExcludedBeforeCallingProvider() throws Exception {
    withRollback(
        () -> {
          db.update(
              "UPDATE financial_statements SET published_at='2026-10-11T00:00:00+07:00'"
                  + " WHERE security_id=?",
              request.securityId());
          var ctx = contexts.build(request);
          assertThat(ctx.coverage().financialPeriods()).isZero();
          assertThat(ctx.points())
              .noneMatch(
                  p -> p.domain().equals("BALANCE_SHEET") || p.domain().equals("INCOME_STATEMENT"));
          assertThat(service.execute(request).status()).isEqualTo("SKIPPED");
        });
    verify(gateway, never()).call(any(), any());
  }

  @Test
  void fiftyNineSessionsCannotPassPriceReadiness() throws Exception {
    withRollback(
        () -> {
          db.update(
              "UPDATE market_prices SET is_canonical=false WHERE security_id=? AND is_canonical AND"
                  + " interval_code='1d' AND id NOT IN (SELECT id FROM market_prices WHERE"
                  + " security_id=? AND is_canonical AND interval_code='1d' ORDER BY"
                  + " price_timestamp DESC LIMIT 59)",
              request.securityId(),
              request.securityId());
          var preview = service.preview(priceRequest());
          assertThat(preview.coverage().marketSessions()).isEqualTo(59);
          assertThat(preview.issues())
              .contains("INSUFFICIENT_PRICE_HISTORY: require 60 daily sessions");
          assertThat(service.execute(priceRequest()).status()).isEqualTo("SKIPPED");
        });
    verify(gateway, never()).call(any(), any());
  }

  @Test
  void stalePriceCannotPassEvenWithSixtyVerifiedSessions() throws Exception {
    withRollback(
        () -> {
          db.update(
              "UPDATE market_prices SET is_canonical=false WHERE security_id=? AND is_canonical"
                  + " AND price_timestamp>='2026-09-24T00:00:00+07:00'",
              request.securityId());
          var preview = service.preview(priceRequest());
          assertThat(preview.coverage().marketSessions()).isEqualTo(60);
          assertThat(preview.issues()).contains("STALE_PRICE_BASE");
          assertThat(service.execute(priceRequest()).status()).isEqualTo("SKIPPED");
        });
    verify(gateway, never()).call(any(), any());
  }

  @Test
  void twoDailyClosesOnTheSameVietnamDateStopBeforeProvider() throws Exception {
    withRollback(
        () -> {
          db.update(
              "UPDATE market_prices SET price_timestamp=(SELECT max(price_timestamp) FROM"
                  + " market_prices WHERE security_id=? AND is_canonical AND interval_code='1d') +"
                  + " interval '1 minute' WHERE id=(SELECT id FROM market_prices WHERE"
                  + " security_id=? AND is_canonical AND interval_code='1d' ORDER BY"
                  + " price_timestamp DESC OFFSET 1 LIMIT 1)",
              request.securityId(),
              request.securityId());
          assertThat(service.preview(priceRequest()).issues()).contains("AMBIGUOUS_DAILY_PRICES");
          assertThat(service.execute(priceRequest()).status()).isEqualTo("SKIPPED");
        });
    verify(gateway, never()).call(any(), any());
  }

  @Test
  void priceFromRejectedSourceVersionCannotBeSentToProvider() throws Exception {
    withRollback(
        () -> {
          db.update(
              "UPDATE data_versions SET status='REJECTED' WHERE id=(SELECT data_version_id FROM"
                  + " market_prices WHERE security_id=? AND is_canonical AND interval_code='1d'"
                  + " ORDER BY price_timestamp DESC LIMIT 1)",
              request.securityId());
          assertThat(service.preview(priceRequest()).issues())
              .contains("PRICE_UNIT_OR_RAW_PROVENANCE_UNVERIFIED");
          assertThat(service.execute(priceRequest()).status()).isEqualTo("SKIPPED");
        });
    verify(gateway, never()).call(any(), any());
  }

  @Test
  void changedSourceWhileModelRespondsCannotPublishTheOldSnapshot() throws Exception {
    doAnswer(
            i -> {
              var response = output(i.getArgument(0));
              db.update(
                  "UPDATE market_prices SET close_price=close_price+1 WHERE id=(SELECT id FROM"
                      + " market_prices WHERE security_id=? AND is_canonical AND interval_code='1d'"
                      + " ORDER BY price_timestamp DESC LIMIT 1)",
                  request.securityId());
              return new LlmGateway.Reply(200, "{}", response.toString(), 1, 1);
            })
        .when(gateway)
        .call(any(), any());
    withRollback(
        () -> {
          var outcome = service.execute(priceRequest());
          assertThat(outcome.status()).isEqualTo("REJECTED");
          assertThat(outcome.issues()).contains("FORECAST_SOURCE_CHANGED");
          assertThat(db.queryForObject("SELECT count(*) FROM llm_results", Integer.class)).isZero();
        });
  }

  @Test
  void differentHorizonsKeepIndependentCurrentResults() throws Exception {
    var price = priceRequest();
    for (int horizon = 1; horizon <= 8; horizon++) {
      var next =
          new ForecastRequest(
              price.securityId(), price.asOfDate(), horizon, price.targets(), false);
      assertThat(service.preview(next).forecastPeriodEnd())
          .isEqualTo(ForecastHorizon.endOfFutureQuarter(price.asOfDate(), horizon));
    }
    var first = service.execute(price);
    var secondRequest =
        new ForecastRequest(price.securityId(), price.asOfDate(), 2, price.targets(), false);
    var second = service.execute(secondRequest);
    assertThat(first.status()).isEqualTo("SUCCESS");
    assertThat(second.status()).isEqualTo("SUCCESS");
    assertThat(second.runId()).isNotEqualTo(first.runId());
    assertThat(store.results(price.securityId(), 10)).hasSize(2)
        .extracting(ForecastDtos.Result::forecastPeriodEnd)
        .containsExactlyInAnyOrder(LocalDate.of(2027,3,31), LocalDate.of(2027,6,30));
    var headers = new HttpHeaders();
    headers.setBearerAuth("isolated-test-admin-credential-not-production");
    var filtered = http.exchange(
        "/api/admin/forecasts/securities/" + price.securityId()
            + "/results?forecastPeriodEnd=2027-06-30",
        HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
    assertThat(filtered.getStatusCode().value()).isEqualTo(200);
    assertThat(filtered.getBody().size()).isEqualTo(1);
    assertThat(filtered.getBody().get(0).path("forecastPeriodEnd").asText()).isEqualTo("2027-06-30");
    assertThat(service.execute(secondRequest).status()).isEqualTo("CACHED");
    assertThat(store.validate(first.runId()).status()).isEqualTo("SUCCESS");
    assertThat(store.results(price.securityId(), 10)).hasSize(2);
    verify(gateway, times(2)).call(any(), any());
  }

  @Test
  void explicitPeriodRequiresAPromptThatSupportsItBeforeCallingProvider() throws Exception {
    withRollback(() -> {
      db.update("UPDATE llm_prompt_templates SET request_schema=jsonb_set(request_schema,'{required}',"
          + " (SELECT jsonb_agg(x) FROM jsonb_array_elements(request_schema->'required') x"
          + " WHERE x<>'\"forecast_period_start\"'::jsonb)) WHERE task_code='FINANCIAL_SCENARIOS'");
      var explicit = new ForecastRequest(request.securityId(), request.asOfDate(), 1,
          request.targets(), false, LocalDate.of(2026,12,31));
      assertThat(service.preview(explicit).eligible()).isFalse();
      assertThat(service.execute(explicit).status()).isEqualTo("CONFIGURATION_REQUIRED");
      assertThat(db.queryForObject("SELECT count(*) FROM llm_runs",Integer.class)).isZero();
    });
    verify(gateway, never()).call(any(),any());
  }

  @Test
  void newCutoffReplacesOnlyTheSamePeriodAndScopeAndKeepsHistory() {
    LocalDate end = LocalDate.of(2026,12,31);
    var firstRequest = new ForecastRequest(request.securityId(), request.asOfDate(), 1, request.targets(), false, end);
    var secondRequest = new ForecastRequest(request.securityId(), request.asOfDate().plusDays(1), 1, request.targets(), false, end);
    var first = service.execute(firstRequest);
    var second = service.execute(secondRequest);
    assertThat(first.status()).isEqualTo("SUCCESS");
    assertThat(second.status()).isEqualTo("SUCCESS");
    assertThat(db.queryForObject("SELECT count(*) FROM llm_results", Integer.class)).isEqualTo(2);
    assertThat(db.queryForObject("SELECT count(*) FROM llm_results WHERE is_current", Integer.class)).isEqualTo(1);
    assertThat(store.results(request.securityId(),10)).singleElement().satisfies(r -> {
      assertThat(r.runId()).isEqualTo(second.runId());
      assertThat(r.forecastPeriodStart()).isEqualTo(LocalDate.of(2026,10,1));
      assertThat(r.forecastPeriodEnd()).isEqualTo(end);
    });
    assertThat(store.validate(first.runId()).issues()).contains("Audit only: never reactivate a superseded result");
    assertThat(service.execute(firstRequest).status()).isEqualTo("CACHED");
    assertThat(db.queryForObject("SELECT count(*) FROM llm_results",Integer.class)).isEqualTo(2);
    assertThat(service.execute(secondRequest).status()).isEqualTo("CACHED");
  }

  @Test
  void historicalCutoffCannotReplaceNewerPeriodResult() {
    LocalDate end = LocalDate.of(2026,12,31);
    var newer = service.execute(new ForecastRequest(request.securityId(), request.asOfDate().plusDays(1),1,request.targets(),false,end));
    var older = service.execute(new ForecastRequest(request.securityId(), request.asOfDate(),1,request.targets(),false,end));
    assertThat(older.status()).isEqualTo("SUCCESS");
    assertThat(store.results(request.securityId(),10)).singleElement().satisfies(r -> assertThat(r.runId()).isEqualTo(newer.runId()));
  }

  @Test
  void rejectedReplacementPreservesPreviousCurrentResult() throws Exception {
    var firstRequest = new ForecastRequest(request.securityId(), request.asOfDate(),1,request.targets(),false,LocalDate.of(2026,12,31));
    var first = service.execute(firstRequest);
    doReturn(new LlmGateway.Reply(200,"{}","{}",1,1)).when(gateway).call(any(),any());
    var failed = service.execute(new ForecastRequest(request.securityId(),request.asOfDate().plusDays(1),1,request.targets(),false,LocalDate.of(2026,12,31)));
    assertThat(failed.status()).isEqualTo("REJECTED");
    assertThat(store.results(request.securityId(),10)).singleElement().satisfies(r -> assertThat(r.runId()).isEqualTo(first.runId()));
  }

  @Test
  void differentTargetScopesDoNotReplaceEachOther() {
    var first = service.execute(request);
    var onlyAssets = new ForecastRequest(request.securityId(),request.asOfDate(),1,EnumSet.of(ForecastRequest.Target.TOTAL_ASSETS),false);
    assertThat(service.execute(onlyAssets).status()).isEqualTo("SUCCESS");
    assertThat(store.results(request.securityId(),10)).hasSize(2);
    assertThat(service.execute(request).status()).isEqualTo("CACHED");
    assertThat(store.validate(first.runId()).status()).isEqualTo("SUCCESS");
  }

  @Test
  void migrationBackfillsPeriodsRanksLegacyDuplicatesAndIsIdempotent() throws Exception {
    var first = service.execute(request);
    var second = service.execute(new ForecastRequest(request.securityId(),request.asOfDate().plusDays(1),1,request.targets(),false));
    String migration;
    try (var stream = new ClassPathResource("db/manual/V20261011_01__forecast_result_periods.sql").getInputStream()) {
      migration = new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
    }
    withRollback(() -> {
      db.execute("DROP INDEX ux_forecast_current_period");
      db.execute("ALTER TABLE llm_results DROP CONSTRAINT ck_forecast_result_period");
      db.update("UPDATE llm_results SET is_current=true,forecast_period_start=null,forecast_period_end=null,target_scope=null");
      db.execute(migration);
      db.execute(migration);
      assertThat(db.queryForObject("SELECT count(*) FROM llm_results",Integer.class)).isEqualTo(2);
      assertThat(db.queryForObject("SELECT llm_run_id FROM llm_results WHERE is_current",UUID.class)).isEqualTo(second.runId());
      assertThat(db.queryForObject("SELECT count(*) FROM llm_results WHERE forecast_period_start IS NOT NULL AND target_scope IS NOT NULL",Integer.class)).isEqualTo(2);
      assertThat(db.queryForObject("SELECT is_current FROM llm_results WHERE llm_run_id=?",Boolean.class,first.runId())).isFalse();
    });
  }

  @Test
  void concurrentIdenticalRequestsClaimOneRunAndCallProviderOnce() throws Exception {
    var entered = new java.util.concurrent.CountDownLatch(1);
    var release = new java.util.concurrent.CountDownLatch(1);
    var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
    doAnswer(
            i -> {
              entered.countDown();
              if (!release.await(20, java.util.concurrent.TimeUnit.SECONDS))
                throw new IllegalStateException("Test release timeout");
              return new LlmGateway.Reply(200, "{}", output(i.getArgument(0)).toString(), 1, 1);
            })
        .when(gateway)
        .call(any(), any());
    try {
      var first = executor.submit(() -> service.execute(priceRequest()));
      assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
      var second = service.execute(priceRequest());
      assertThat(second.status()).isEqualTo("RUNNING");
      release.countDown();
      var completed = first.get(20, java.util.concurrent.TimeUnit.SECONDS);
      assertThat(completed.status()).isEqualTo("SUCCESS");
      assertThat(second.runId()).isEqualTo(completed.runId());
      assertThat(db.queryForObject("SELECT count(*) FROM llm_runs", Integer.class)).isEqualTo(1);
      assertThat(service.execute(priceRequest()).status()).isEqualTo("CACHED");
      verify(gateway, times(1)).call(any(), any());
    } finally {
      release.countDown();
      executor.shutdownNow();
      executor.awaitTermination(20, java.util.concurrent.TimeUnit.SECONDS);
    }
  }

  @Test
  void apiReportsCurrentValidationRoundAndKeepsPreviousAudit() {
    var outcome = service.execute(priceRequest());
    var firstRound = store.run(outcome.runId()).validationRoundId();
    var headers = new HttpHeaders();
    headers.setBearerAuth("isolated-test-admin-credential-not-production");
    var revalidate =
        http.exchange(
            "/api/admin/forecasts/runs/" + outcome.runId() + "/revalidate",
            HttpMethod.POST,
            new HttpEntity<>(headers),
            JsonNode.class);
    assertThat(revalidate.getBody().path("status").asText()).isEqualTo("SUCCESS");
    var runResponse =
        http.exchange(
                "/api/admin/forecasts/runs/" + outcome.runId(),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class)
            .getBody();
    String currentRound = runResponse.path("validationRoundId").asText();
    assertThat(currentRound).isNotEqualTo(firstRound.toString());
    var audits = new ArrayList<JsonNode>();
    runResponse.path("validations").forEach(audits::add);
    assertThat(audits).hasSize(14);
    assertThat(
            audits.stream()
                .filter(v -> currentRound.equals(v.path("validation_round_id").asText()))
                .toList())
        .hasSize(7)
        .allSatisfy(v -> assertThat(v.path("result_status").asText()).isEqualTo("PASS"));
    assertThat(service.execute(priceRequest()).status()).isEqualTo("CACHED");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"wrong-date", "older-base", "wrong-order", "missing-target"})
  void incorrectPriceResponseCannotPublish(String fault) throws Exception {
    doAnswer(
            i -> {
              var response =
                  (com.fasterxml.jackson.databind.node.ObjectNode) output(i.getArgument(0));
              var forecasts =
                  (com.fasterxml.jackson.databind.node.ArrayNode) response.path("forecasts");
              var forecast = (com.fasterxml.jackson.databind.node.ObjectNode) forecasts.get(0);
              switch (fault) {
                case "wrong-date" -> response.put("forecast_period_end", "2027-06-30");
                case "older-base" ->
                    forecast.put(
                        "base_point_id",
                        forecast.path("base").path("evidence_ids").get(1).asText());
                case "wrong-order" ->
                    ((com.fasterxml.jackson.databind.node.ObjectNode) forecast.path("bull"))
                        .put("growth_percent", -10);
                case "missing-target" -> forecasts.removeAll();
                default -> throw new IllegalArgumentException(fault);
              }
              return new LlmGateway.Reply(200, "{}", response.toString(), 1, 1);
            })
        .when(gateway)
        .call(any(), any());
    assertThat(service.execute(priceRequest()).status()).isEqualTo("REJECTED");
    assertThat(store.results(request.securityId(), 10)).isEmpty();
  }
}
