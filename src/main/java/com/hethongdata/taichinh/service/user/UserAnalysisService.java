package com.hethongdata.taichinh.service.user;
import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.service.forecast.ForecastRunStore;
import com.hethongdata.taichinh.service.llm.LlmRunStore;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;

/** Reuses publication guards, source hashes and validation policy from the existing domain readers. */
@Service @Transactional(readOnly=true,timeout=20,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class UserAnalysisService {
  private final UserSecurityService securities;private final UserNewsService news;private final UserReadSupport read;
  private final LlmRunStore newsResults;private final ForecastRunStore forecasts;
  public UserAnalysisService(UserSecurityService securities,UserNewsService news,UserReadSupport read,LlmRunStore newsResults,ForecastRunStore forecasts) {
    this.securities=securities;this.news=news;this.read=read;this.newsResults=newsResults;this.forecasts=forecasts;
  }
  public Data<List<Map<String,Object>>> news(UUID id) {
    news.visible(id);var results=new ArrayList<Map<String,Object>>();
    for(JsonNode result:newsResults.results(id)) {
      if(!Set.of("VALID","WARNING").contains(result.path("quality_status").asText())) continue;
      var row=new LinkedHashMap<String,Object>();row.put("id",result.path("id").asText());row.put("task",result.path("task_code").asText());row.put("qualityStatus",result.path("quality_status").asText());
      row.put("createdAt",result.path("created_at").asText());row.put("data",read.publicJson(result.path("data")));results.add(row);
    }
    return data(List.copyOf(results),"Published current results only: existing source-hash, enabled-template, validation-round and policy guards apply. Empty results do not trigger AI execution.","Quality describes validation of the response, not independently verified financial facts or prediction accuracy.");
  }
  public Data<List<Map<String,Object>>> forecasts(String symbol,String exchange,int limit) {
    if(limit<1||limit>20) throw new IllegalArgumentException("limit must be 1..20");
    var security=securities.resolve(symbol,exchange);UUID id=UUID.fromString(security.get("id").toString());var results=new ArrayList<Map<String,Object>>();
    for(var result:forecasts.results(id,limit)) {
      if(!Set.of("VALID","WARNING").contains(result.qualityStatus())) continue;
      var row=new LinkedHashMap<String,Object>();row.put("id",result.id());row.put("symbol",security.get("symbol"));row.put("exchange",security.get("exchange"));row.put("securityId",id);
      row.put("asOfDate",result.asOfDate());row.put("forecastPeriodStart",result.forecastPeriodStart());row.put("forecastPeriodEnd",result.forecastPeriodEnd());row.put("targetScope",result.targetScope());row.put("qualityStatus",result.qualityStatus());row.put("data",read.publicJson(result.data()));results.add(row);
    }
    return data(List.copyOf(results),"Existing publication reader checks current source snapshot, validation policy and the complete blocking-validation round. At most limit current candidates are examined; this is not a paginated history.","These are AI scenarios with evidence and limitations, not certified forecasts, calibrated probabilities, target prices or local-AI deployment results. Reads never execute a model.");
  }
}
