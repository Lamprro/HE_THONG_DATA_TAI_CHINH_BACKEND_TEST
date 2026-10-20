package com.hethongdata.taichinh.service.admin;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.controller.admin.*;
import com.hethongdata.taichinh.service.financial.HistoricalFinancialMetricService;
import java.util.*;
import org.junit.jupiter.api.Test;

class AdminAiActionServiceTests {
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  final NewsLlmAdminController news=mock(NewsLlmAdminController.class);
  final FinancialForecastAdminController forecast=mock(FinancialForecastAdminController.class);
  final NewsRecoveryAdminController recovery=mock(NewsRecoveryAdminController.class);
  final AdminAiActionService service=new AdminAiActionService(news,forecast,recovery,mock(HistoricalFinancialMetricService.class),json,jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
  @Test void forecastConstraintsAreValidatedBeforeNativeExecution() throws Exception {
    var in=json.readTree("{\"securityId\":\""+UUID.randomUUID()+"\",\"asOfDate\":\"2026-10-16\",\"horizonQuarters\":0,\"targets\":[\"STOCK_PRICE\"]}");
    assertThatThrownBy(()->service.resource("FORECAST_EXECUTE",in)).isInstanceOf(IllegalArgumentException.class);verifyNoInteractions(forecast);
    ((com.fasterxml.jackson.databind.node.ObjectNode)in).put("horizonQuarters",4);
    assertThat(service.resource("FORECAST_PREVIEW",in)).startsWith("forecast:");service.execute("FORECAST_PREVIEW",in);
    verify(forecast).preview(any());verify(forecast,never()).execute(any());
  }
  @Test void recoveryReviewAndAnalysisRemainSeparateNativeActions() throws Exception {
    UUID run=UUID.randomUUID();var input=json.readTree("{\"runId\":\""+run+"\",\"request\":{\"reviewer\":\"Admin fixture\",\"reason\":\"Reviewed isolated proposal\"}}");
    service.resource("RECOVERY_APPROVE",input);service.execute("RECOVERY_APPROVE",input);
    verify(recovery).approve(eq(run),any());verify(recovery,never()).analyze(any());
    when(recovery.analyze(run)).thenThrow(new IllegalArgumentException("RECOVERY_NOT_APPROVED"));
    assertThatThrownBy(()->service.execute("RECOVERY_ANALYZE",input)).hasMessage("RECOVERY_NOT_APPROVED");
  }
  @Test void unsupportedTasksAndInvalidReviewFieldsAreRejectedBeforeNativeCalls() throws Exception {
    assertThatThrownBy(()->service.resource("NEWS_EXECUTE",json.readTree("{\"articleId\":\""+UUID.randomUUID()+"\",\"task\":\"INVENT_CONTENT\"}"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->service.resource("RECOVERY_REJECT",json.readTree("{\"runId\":\""+UUID.randomUUID()+"\",\"request\":{\"reviewer\":\"\",\"reason\":\"\"}}"))).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(news,recovery);
  }
}
