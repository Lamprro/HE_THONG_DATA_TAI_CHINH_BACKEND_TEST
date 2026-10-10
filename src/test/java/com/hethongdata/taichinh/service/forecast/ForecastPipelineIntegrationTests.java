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
              "raw_payloads",
              "data_versions",
              "companies",
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
      for (String t :
          List.of(
              "data_sources",
              "raw_payloads",
              "data_versions",
              "companies",
              "securities",
              "financial_periods",
              "financial_statements",
              "financial_statement_items",
              "financial_metrics",
              "metric_definitions",
              "market_prices")) s.execute("INSERT INTO " + t + " SELECT * FROM public." + t);
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
  @Autowired ForecastValidationService validations;
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
            + " is_active=true,severity='ERROR',rule_config=jsonb_set(rule_config,'{version}','\"1\"')");
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
  void differentHorizonsUseDifferentCallsAndDoNotReactivateSupersededResults() throws Exception {
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
    assertThat(store.results(price.securityId(), 10))
        .singleElement()
        .satisfies(
            r -> assertThat(r.data().path("forecast_period_end").asText()).isEqualTo("2027-06-30"));
    assertThat(service.execute(secondRequest).status()).isEqualTo("CACHED");
    assertThat(store.validate(first.runId()).issues())
        .contains("Audit only: never reactivate a superseded result");
    assertThat(store.results(price.securityId(), 10))
        .singleElement()
        .satisfies(r -> assertThat(r.runId()).isEqualTo(second.runId()));
    verify(gateway, times(2)).call(any(), any());
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
