package com.hethongdata.taichinh.service.user;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.dto.forecast.ForecastDtos.Result;
import com.hethongdata.taichinh.repository.jdbc.user.UserNewsQueryRepository;
import com.hethongdata.taichinh.repository.jdbc.user.UserReadJdbcRepository;
import com.hethongdata.taichinh.service.forecast.ForecastRunStore;
import com.hethongdata.taichinh.service.llm.LlmRunStore;
import java.util.*;
import org.junit.jupiter.api.Test;

class UserNewsServiceTests {
  final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
  final LlmRunStore results = mock(LlmRunStore.class);
  final ForecastRunStore forecasts = mock(ForecastRunStore.class);
  final UserReadJdbcRepository jdbcRepo = mock(UserReadJdbcRepository.class);
  final UserReadSupport read = spy(new UserReadSupport(jdbcRepo, json));
  final UserNewsQueryRepository newsRepo = spy(new UserNewsQueryRepository(jdbcRepo));
  final UserNewsService service = new UserNewsService(newsRepo, read, results, forecasts);
  static final String VISIBLE = "Published news article";

  @Test void publicForecastDelegatesPublicationChecksAndOmitsRunInternals() throws Exception {
    UUID id = UUID.randomUUID(), run = UUID.randomUUID(), resultId = UUID.randomUUID();
    doReturn(Map.of("id", id.toString(), "symbol", "FPT", "exchange", "HOSE")).when(read).resolve("FPT", null);
    var payload = json.readTree("""
        {"status":"PARTIAL","forecasts":[{"metric_code":"STOCK_PRICE","base_point_id":"PRICE:73017",
        "base":{"growth_percent":5,"evidence_ids":["PRICE:73017"],
        "rationale":"So với FSI:2f03bff7-4ae0-45a6-bed4-d6842bbaba66 và PRICE:73017"}}],
        "calculated_values":[{"metric_code":"STOCK_PRICE","base":52027.5,"sourcePointIds":["PRICE:73017"]}],
        "limitations":["fixture only"]}
        """);
    when(forecasts.results(id, 5)).thenReturn(List.of(new Result(
        resultId, run, UUID.randomUUID(), id,
        java.time.LocalDate.parse("2026-10-01"), java.time.LocalDate.parse("2027-01-01"),
        java.time.LocalDate.parse("2027-03-31"), "REVENUE", "WARNING", payload)));
    var value = service.forecasts("FPT", null, 5);
    assertThat(value.data()).hasSize(1);
    assertThat(json.writeValueAsString(value)).contains("PARTIAL", "fixture only", "dữ liệu nguồn", "\"52027.5\"")
        .doesNotContain(resultId.toString(), id.toString(), run.toString(), "request_payload", "prompt_template",
            "attempts", "base_point_id", "evidence_ids", "sourcePointIds", "PRICE:73017", "2f03bff7");
    assertThat(payload.toString()).contains("PRICE:73017", "evidence_ids");
    assertThat(value.data().getFirst())
        .containsEntry("forecastPeriodStart", java.time.LocalDate.parse("2027-01-01"))
        .containsEntry("forecastPeriodEnd", java.time.LocalDate.parse("2027-03-31"))
        .containsEntry("targetScope", "REVENUE");
    verify(forecasts).results(id, 5);
    verifyNoMoreInteractions(forecasts);
    verifyNoInteractions(results);
    verify(jdbcRepo, never()).one(anyString(), anyMap(), eq(VISIBLE));
  }

  @Test void newsUsesNativeCurrentSourceAndPolicyReaderAndDoesNotExposeOperationalFields() throws Exception {
    UUID id = UUID.randomUUID();
    doReturn(Map.of("id", id)).when(jdbcRepo).one(anyString(), anyMap(), eq(VISIBLE));
    when(results.results(id)).thenReturn(List.of(json.readTree(
        "{\"id\":\"fixture-result-id\",\"task_code\":\"NEWS_SUMMARY\",\"quality_status\":\"VALID\",\"created_at\":\"2026-10-01T00:00:00Z\",\"data\":{\"value\":9007199254740993,\"evidence\":[{\"segment_id\":\"s01\",\"quote\":\"Quoted text\"}],\"company_id\":\"internal-company\"},\"attempts\":[\"private\"]}")));
    String value = json.writeValueAsString(service.analysis(id));
    assertThat(value).contains("\"9007199254740993\"", "Quoted text")
        .doesNotContain("attempts", "private", "fixture-result-id", "segment_id", "company_id", "internal-company");
    verify(jdbcRepo).one(contains("n.id=:id"), eq(Map.of("id", id)), eq(VISIBLE));
    verify(results).results(id);
    verifyNoMoreInteractions(results);
    verifyNoInteractions(forecasts);
  }

  @Test void missingResultsStayEmptyAndBadLimitDoesNotReadOrExecuteAnything() {
    UUID id = UUID.randomUUID();
    doReturn(Map.of("id", id)).when(jdbcRepo).one(anyString(), anyMap(), eq(VISIBLE));
    when(results.results(id)).thenReturn(List.of());
    assertThat(service.analysis(id).data()).isEmpty();
    assertThatThrownBy(() -> service.forecasts("FPT", null, 21)).isInstanceOf(IllegalArgumentException.class);
    verify(read, never()).resolve(any(), any());
    verifyNoInteractions(forecasts);
  }
}
