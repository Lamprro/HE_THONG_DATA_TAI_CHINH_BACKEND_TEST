package com.hethongdata.taichinh.service.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.repository.jdbc.user.UserNewsQueryRepository;
import com.hethongdata.taichinh.service.forecast.ForecastRunStore;
import com.hethongdata.taichinh.service.llm.LlmRunStore;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;

/**
 * Published news and the published AI analysis built on it (news analysis and financial scenarios).
 * Analysis reuses publication guards, source hashes and validation policy from the existing domain readers.
 */
@Service
@Transactional(readOnly=true,timeout=15,isolation=Isolation.REPEATABLE_READ)
public class UserNewsService {
  private final UserNewsQueryRepository newsRepo;
  private final UserReadSupport read;
  private final LlmRunStore newsResults;
  private final ForecastRunStore forecasts;

  @Autowired
  public UserNewsService(UserNewsQueryRepository newsRepo, UserReadSupport read, LlmRunStore newsResults, ForecastRunStore forecasts) {
    this.newsRepo = newsRepo;
    this.read = read;
    this.newsResults = newsResults;
    this.forecasts = forecasts;
  }

  // ---- news ----
  public Page<Map<String,Object>> news(String symbol,String exchange,String search,LocalDate from,LocalDate to,int page,int size) {
    return newsRepo.queryNews(symbol, exchange, search, from, to, page, size);
  }

  public Map<String,Object> visible(UUID id) {
    return newsRepo.findVisibleArticle(id);
  }

  public Data<Map<String,Object>> detail(UUID id) {
    var article = new LinkedHashMap<>(visible(id));
    article.putAll(newsRepo.findArticleContent(id));
    var links = new ArrayList<>(newsRepo.findArticleCompanies(id));
    article.put("companyLinksTruncated", links.size() > 100);
    if (links.size() > 100) article.put("companies", links.subList(0, 100));
    else article.put("companies", links);
    return data(article,
        "Content is stored plain text, not HTML. Null body means unavailable; use the source URL.",
        "SOURCE_JOB is a source association, not proof that the body mentions that symbol. Relation matchMethod remains explicit.");
  }

  // ---- published AI analysis (timeout 20) ----
  @Transactional(readOnly=true,timeout=20,isolation=Isolation.REPEATABLE_READ)
  public Data<List<Map<String,Object>>> analysis(UUID id) {
    visible(id);
    var results = new ArrayList<Map<String,Object>>();
    for (JsonNode result : newsResults.results(id)) {
      if (!Set.of("VALID", "WARNING").contains(result.path("quality_status").asText())) continue;
      var row = new LinkedHashMap<String,Object>();
      row.put("task", result.path("task_code").asText());
      row.put("qualityStatus", result.path("quality_status").asText());
      row.put("createdAt", result.path("created_at").asText());
      row.put("data", read.publicAiJson(result.path("data")));
      results.add(row);
    }
    return data(List.copyOf(results),
        "Published current results only: existing source-hash, enabled-template, validation-round and policy guards apply. Empty results do not trigger AI execution.",
        "Quality describes validation of the response, not independently verified financial facts or prediction accuracy.");
  }

  @Transactional(readOnly=true,timeout=20,isolation=Isolation.REPEATABLE_READ)
  public Data<List<Map<String,Object>>> forecasts(String symbol,String exchange,int limit) {
    if (limit < 1 || limit > 20) throw new IllegalArgumentException("limit must be 1..20");
    var security = read.resolve(symbol, exchange);
    UUID id = UUID.fromString(security.get("id").toString());
    var results = new ArrayList<Map<String,Object>>();
    for (var result : forecasts.results(id, limit)) {
      if (!Set.of("VALID", "WARNING").contains(result.qualityStatus())) continue;
      var row = new LinkedHashMap<String,Object>();
      row.put("symbol", security.get("symbol"));
      row.put("exchange", security.get("exchange"));
      row.put("asOfDate", result.asOfDate());
      row.put("forecastPeriodStart", result.forecastPeriodStart());
      row.put("forecastPeriodEnd", result.forecastPeriodEnd());
      row.put("targetScope", result.targetScope());
      row.put("qualityStatus", result.qualityStatus());
      row.put("data", read.publicAiJson(result.data()));
      results.add(row);
    }
    return data(List.copyOf(results),
        "Existing publication reader checks current source snapshot, validation policy and the complete blocking-validation round. At most limit current candidates are examined; this is not a paginated history.",
        "These are AI scenarios with evidence and limitations, not certified forecasts, calibrated probabilities, or target prices. Reads never execute a model.");
  }
}
