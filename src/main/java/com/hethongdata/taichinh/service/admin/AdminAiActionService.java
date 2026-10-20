package com.hethongdata.taichinh.service.admin;

import com.fasterxml.jackson.databind.*;
import com.hethongdata.taichinh.controller.admin.*;
import com.hethongdata.taichinh.dto.forecast.ForecastRequest;
import com.hethongdata.taichinh.dto.news.NewsRecoveryDtos.*;
import com.hethongdata.taichinh.service.financial.HistoricalFinancialMetricService;
import com.hethongdata.taichinh.service.llm.LlmPromptCatalog;
import jakarta.validation.Validator;
import java.util.*;
import org.springframework.stereotype.Service;

/** Typed allowlist invoking existing controllers/services; never arbitrary HTTP, SQL or reflection dispatch. */
@Service
public class AdminAiActionService {
  public static final Set<String> ACTIONS=Set.of("NEWS_EXECUTE","NEWS_REVALIDATE","NEWS_VALIDATE_PENDING",
      "FORECAST_PREVIEW","FORECAST_EXECUTE","FORECAST_METRICS","FORECAST_REVALIDATE","FORECAST_VALIDATE_PENDING",
      "RECOVERY_EXECUTE","RECOVERY_APPROVE","RECOVERY_REJECT","RECOVERY_ANALYZE","HISTORICAL_METRICS");
  private final NewsLlmAdminController news; private final FinancialForecastAdminController forecast;
  private final NewsRecoveryAdminController recovery; private final HistoricalFinancialMetricService metrics;
  private final ObjectMapper json; private final Validator validator;
  public AdminAiActionService(NewsLlmAdminController news,FinancialForecastAdminController forecast,
      NewsRecoveryAdminController recovery,HistoricalFinancialMetricService metrics,ObjectMapper json,Validator validator) {
    this.news=news;this.forecast=forecast;this.recovery=recovery;this.metrics=metrics;this.json=json;this.validator=validator;
  }
  private UUID id(JsonNode input,String key) { return UUID.fromString(input.path(key).asText()); }
  private <T> T body(JsonNode input,Class<T> type) {
    T value=json.convertValue(input,type);var errors=validator.validate(value);
    if(!errors.isEmpty()) {var error=errors.iterator().next();throw new IllegalArgumentException(error.getPropertyPath()+" "+error.getMessage());}
    return value;
  }
  public String resource(String action,JsonNode in) {
    if(!ACTIONS.contains(action)) throw new IllegalArgumentException("Unsupported AI/admin action");
    if(action.startsWith("FORECAST_") && !action.endsWith("REVALIDATE") && !action.endsWith("PENDING")) {
      var request=body(in,ForecastRequest.class);return "forecast:"+request.securityId();
    }
    if(action.equals("HISTORICAL_METRICS")) { var request=body(in,HistoricalFinancialMetricService.Request.class);
      if(request.symbol()==null || request.startDate()==null || request.endDate()==null) throw new IllegalArgumentException("symbol, startDate and endDate required");
      return "metrics:"+request.symbol().toUpperCase(Locale.ROOT); }
    if(action.endsWith("PENDING")) { int n=in.path("limit").asInt(5);if(n<1||n>20) throw new IllegalArgumentException("AI batch limit must be 1..20");return action; }
    if(action.equals("NEWS_EXECUTE")) {
      String task=in.path("task").asText("ALL"); if(!"ALL".equals(task)&&!LlmPromptCatalog.TASKS.contains(task)) throw new IllegalArgumentException("Unsupported news task");
      return "article:"+id(in,"articleId");
    }
    if(action.equals("RECOVERY_EXECUTE")) { body(in.path("request"),ExecuteRequest.class);return "article:"+id(in,"articleId"); }
    if(action.equals("RECOVERY_APPROVE") || action.equals("RECOVERY_REJECT")) body(in.path("request"),ReviewRequest.class);
    return "ai-run:"+id(in,"runId");
  }
  public Object execute(String action,JsonNode in) {
    return switch(action) {
      case "NEWS_EXECUTE" -> "ALL".equals(in.path("task").asText("ALL"))?news.execute(id(in,"articleId")):news.executeTask(id(in,"articleId"),in.path("task").asText());
      case "NEWS_REVALIDATE" -> news.revalidate(id(in,"runId"));
      case "NEWS_VALIDATE_PENDING" -> news.validatePending(in.path("limit").asInt(5));
      case "FORECAST_PREVIEW" -> forecast.preview(body(in,ForecastRequest.class));
      case "FORECAST_EXECUTE" -> forecast.execute(body(in,ForecastRequest.class));
      case "FORECAST_METRICS" -> forecast.metrics(body(in,ForecastRequest.class));
      case "FORECAST_REVALIDATE" -> forecast.revalidate(id(in,"runId"));
      case "FORECAST_VALIDATE_PENDING" -> forecast.pending(in.path("limit").asInt(5));
      case "RECOVERY_EXECUTE" -> recovery.execute(id(in,"articleId"),body(in.path("request"),ExecuteRequest.class));
      case "RECOVERY_APPROVE" -> recovery.approve(id(in,"runId"),body(in.path("request"),ReviewRequest.class));
      case "RECOVERY_REJECT" -> recovery.reject(id(in,"runId"),body(in.path("request"),ReviewRequest.class));
      case "RECOVERY_ANALYZE" -> recovery.analyze(id(in,"runId"));
      case "HISTORICAL_METRICS" -> metrics.calculate(body(in,HistoricalFinancialMetricService.Request.class));
      default -> throw new IllegalArgumentException("Unsupported AI/admin action");
    };
  }
}
