package com.hethongdata.taichinh.service.user;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.service.forecast.ForecastRunStore;
import com.hethongdata.taichinh.service.llm.LlmRunStore;
import com.hethongdata.taichinh.dto.forecast.ForecastDtos.Result;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
class UserAnalysisServiceTests {
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();final UserSecurityService securities=mock(UserSecurityService.class);
  final UserNewsService news=mock(UserNewsService.class);final LlmRunStore results=mock(LlmRunStore.class);final ForecastRunStore forecasts=mock(ForecastRunStore.class);
  final UserReadSupport read=new UserReadSupport(new JdbcTemplate(mock(javax.sql.DataSource.class)),json);
  final UserAnalysisService service=new UserAnalysisService(securities,news,read,results,forecasts);
  @Test void publicForecastDelegatesPublicationChecksAndOmitsRunInternals() throws Exception {
    UUID id=UUID.randomUUID(),run=UUID.randomUUID();when(securities.resolve("FPT",null)).thenReturn(Map.of("id",id.toString(),"symbol","FPT","exchange","HOSE"));
    var payload=json.readTree("{\"status\":\"PARTIAL\",\"forecasts\":[],\"limitations\":[\"fixture only\"]}");
    when(forecasts.results(id,5)).thenReturn(List.of(new Result(UUID.randomUUID(),run,UUID.randomUUID(),id,java.time.LocalDate.parse("2026-10-01"),"WARNING",payload)));
    var value=service.forecasts("FPT",null,5);assertThat(value.data()).hasSize(1);
    assertThat(json.writeValueAsString(value)).contains("PARTIAL","fixture only").doesNotContain(run.toString(),"request_payload","prompt_template","attempts");
    verify(forecasts).results(id,5);verifyNoMoreInteractions(forecasts);verifyNoInteractions(news,results);
  }
  @Test void newsUsesNativeCurrentSourceAndPolicyReaderAndDoesNotExposeOperationalFields() throws Exception {
    UUID id=UUID.randomUUID();when(news.visible(id)).thenReturn(Map.of("id",id));
    when(results.results(id)).thenReturn(List.of(json.readTree("{\"id\":\"fixture\",\"task_code\":\"NEWS_SUMMARY\",\"quality_status\":\"VALID\",\"created_at\":\"2026-10-01T00:00:00Z\",\"data\":{\"value\":9007199254740993},\"attempts\":[\"private\"]}")));
    String value=json.writeValueAsString(service.news(id));assertThat(value).contains("\"9007199254740993\"").doesNotContain("attempts","private");
    verify(news).visible(id);verify(results).results(id);verifyNoMoreInteractions(results);verifyNoInteractions(forecasts);
  }
  @Test void missingResultsStayEmptyAndBadLimitDoesNotReadOrExecuteAnything() {
    UUID id=UUID.randomUUID();when(results.results(id)).thenReturn(List.of());assertThat(service.news(id).data()).isEmpty();
    assertThatThrownBy(()->service.forecasts("FPT",null,21)).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(securities,forecasts);
  }
}
