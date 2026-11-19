package com.hethongdata.taichinh.service.user;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.service.forecast.ForecastRunStore;
import com.hethongdata.taichinh.service.llm.LlmRunStore;
import com.hethongdata.taichinh.dto.forecast.ForecastDtos.Result;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
class UserNewsServiceTests {
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  final LlmRunStore results=mock(LlmRunStore.class);final ForecastRunStore forecasts=mock(ForecastRunStore.class);
  final UserReadSupport read=spy(new UserReadSupport(new JdbcTemplate(mock(javax.sql.DataSource.class)),json));
  final UserNewsService service=new UserNewsService(read,results,forecasts);
  static final String VISIBLE="Published news article";
  @Test void publicForecastDelegatesPublicationChecksAndOmitsRunInternals() throws Exception {
    UUID id=UUID.randomUUID(),run=UUID.randomUUID();doReturn(Map.of("id",id.toString(),"symbol","FPT","exchange","HOSE")).when(read).resolve("FPT",null);
    var payload=json.readTree("{\"status\":\"PARTIAL\",\"forecasts\":[],\"limitations\":[\"fixture only\"]}");
    when(forecasts.results(id,5)).thenReturn(List.of(new Result(UUID.randomUUID(),run,UUID.randomUUID(),id,java.time.LocalDate.parse("2026-10-01"),java.time.LocalDate.parse("2027-01-01"),java.time.LocalDate.parse("2027-03-31"),"REVENUE","WARNING",payload)));
    var value=service.forecasts("FPT",null,5);assertThat(value.data()).hasSize(1);
    assertThat(json.writeValueAsString(value)).contains("PARTIAL","fixture only").doesNotContain(run.toString(),"request_payload","prompt_template","attempts");
    assertThat(value.data().getFirst()).containsEntry("forecastPeriodStart",java.time.LocalDate.parse("2027-01-01")).containsEntry("forecastPeriodEnd",java.time.LocalDate.parse("2027-03-31")).containsEntry("targetScope","REVENUE");
    verify(forecasts).results(id,5);verifyNoMoreInteractions(forecasts);verifyNoInteractions(results);verify(read,never()).one(anyString(),anyMap(),eq(VISIBLE));
  }
  @Test void newsUsesNativeCurrentSourceAndPolicyReaderAndDoesNotExposeOperationalFields() throws Exception {
    UUID id=UUID.randomUUID();doReturn(Map.of("id",id)).when(read).one(anyString(),anyMap(),eq(VISIBLE));
    when(results.results(id)).thenReturn(List.of(json.readTree("{\"id\":\"fixture\",\"task_code\":\"NEWS_SUMMARY\",\"quality_status\":\"VALID\",\"created_at\":\"2026-10-01T00:00:00Z\",\"data\":{\"value\":9007199254740993},\"attempts\":[\"private\"]}")));
    String value=json.writeValueAsString(service.analysis(id));assertThat(value).contains("\"9007199254740993\"").doesNotContain("attempts","private");
    verify(read).one(contains("n.id=:id"),eq(Map.of("id",id)),eq(VISIBLE));verify(results).results(id);verifyNoMoreInteractions(results);verifyNoInteractions(forecasts);
  }
  @Test void missingResultsStayEmptyAndBadLimitDoesNotReadOrExecuteAnything() {
    UUID id=UUID.randomUUID();doReturn(Map.of("id",id)).when(read).one(anyString(),anyMap(),eq(VISIBLE));when(results.results(id)).thenReturn(List.of());assertThat(service.analysis(id).data()).isEmpty();
    assertThatThrownBy(()->service.forecasts("FPT",null,21)).isInstanceOf(IllegalArgumentException.class);verify(read,never()).resolve(any(),any());verifyNoInteractions(forecasts);
  }
}
