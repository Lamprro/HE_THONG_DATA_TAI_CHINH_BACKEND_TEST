package com.hethongdata.taichinh.service.admin;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.service.financial.HistoricalFinancialMetricService;
import com.hethongdata.taichinh.service.forecast.*;
import com.hethongdata.taichinh.service.llm.*;
import com.hethongdata.taichinh.service.news.recovery.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AdminAiActionServiceTests {
  final ObjectMapper json=new ObjectMapper().findAndRegisterModules();
  final NewsLlmService news=mock(NewsLlmService.class);
  final FinancialForecastService forecast=mock(FinancialForecastService.class);
  final NewsRecoveryService recovery=mock(NewsRecoveryService.class);
  final NewsRecoveryStore recoveryRuns=mock(NewsRecoveryStore.class);
  final com.hethongdata.taichinh.service.localai.LocalAiTrainerService trainer=mock(com.hethongdata.taichinh.service.localai.LocalAiTrainerService.class);
  final AdminAiActionService service=new AdminAiActionService(news,mock(LlmRunStore.class),forecast,mock(ForecastContextService.class),
      mock(ForecastRunStore.class),recovery,recoveryRuns,mock(HistoricalFinancialMetricService.class),trainer,json,
      jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator());
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
    verify(recoveryRuns).approve(eq(run),any());verify(recovery,never()).analyzeApproved(any());
    when(recovery.analyzeApproved(run)).thenThrow(new IllegalArgumentException("RECOVERY_NOT_APPROVED"));
    assertThatThrownBy(()->service.execute("RECOVERY_ANALYZE",input)).hasMessage("RECOVERY_NOT_APPROVED");
  }
  @Test void unsupportedTasksAndInvalidReviewFieldsAreRejectedBeforeNativeCalls() throws Exception {
    assertThatThrownBy(()->service.resource("NEWS_EXECUTE",json.readTree("{\"articleId\":\""+UUID.randomUUID()+"\",\"task\":\"INVENT_CONTENT\"}"))).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(()->service.resource("RECOVERY_REJECT",json.readTree("{\"runId\":\""+UUID.randomUUID()+"\",\"request\":{\"reviewer\":\"\",\"reason\":\"\"}}"))).isInstanceOf(IllegalArgumentException.class);
    verifyNoInteractions(news,recovery,recoveryRuns);
  }
  @Test void localAiActionsDispatchToTrainer() throws Exception {
    var retrainInput=json.readTree("{\"reason\":\"Scheduled quarterly refresh\",\"workers\":2}");
    assertThat(service.resource("LOCAL_AI_RETRAIN",retrainInput)).isEqualTo("local-ai:training");
    service.execute("LOCAL_AI_RETRAIN",retrainInput);
    verify(trainer).retrain(any(),eq("MANUAL"),eq("admin"),eq("Scheduled quarterly refresh"),eq(2));

    UUID runId=UUID.randomUUID();
    var publishInput=json.readTree("{\"trainingRunId\":\""+runId+"\",\"variant\":\"V1\",\"reason\":\"Manual acceptance passed\"}");
    assertThat(service.resource("LOCAL_AI_PUBLISH",publishInput)).isEqualTo("local-ai:publish:"+runId);
    service.execute("LOCAL_AI_PUBLISH",publishInput);
    verify(trainer).publish(eq(runId),eq("V1"),eq("Manual acceptance passed"));

    var rollbackInput=json.readTree("{\"reason\":\"Rollback to superseded\"}");
    assertThat(service.resource("LOCAL_AI_ROLLBACK",rollbackInput)).isEqualTo("local-ai:rollback");
    service.execute("LOCAL_AI_ROLLBACK",rollbackInput);
    verify(trainer).rollback(eq("Rollback to superseded"));
  }
}
