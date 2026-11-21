package com.hethongdata.taichinh.service.user;

import com.hethongdata.taichinh.repository.jdbc.user.UserSecurityQueryRepository;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;

/**
 * Security/company master data, per-security data status and the bounded overview.
 * The overview composes existing readers; it never schedules ingestion, trains or invokes a model.
 */
@Service
@Transactional(readOnly=true,timeout=15,isolation=Isolation.REPEATABLE_READ)
public class UserSecurityService {
  private final UserSecurityQueryRepository securityRepo;
  private final UserReadSupport read;
  private final UserMarketService market;
  private final UserFinancialService financial;
  private final UserNewsService news;
  private final UserLocalAiService localAi;

  @Autowired
  public UserSecurityService(
      UserSecurityQueryRepository securityRepo,
      UserReadSupport read,
      UserMarketService market,
      UserFinancialService financial,
      UserNewsService news,
      UserLocalAiService localAi) {
    this.securityRepo = securityRepo;
    this.read = read;
    this.market = market;
    this.financial = financial;
    this.news = news;
    this.localAi = localAi;
  }

  // ---- master data ----
  public Page<Map<String,Object>> securities(String search,String exchange,boolean activeOnly,int page,int size) {
    return securityRepo.querySecurities(search, exchange, activeOnly, page, size);
  }

  public Data<Map<String,Object>> detail(String symbol,String exchange) {
    var security = new LinkedHashMap<>(read.resolve(symbol, exchange));
    security.put("company", companyRow(UUID.fromString(security.get("companyId").toString())));
    return data(security, "Master profile is a stored snapshot, not a live provider request.");
  }

  private Map<String,Object> companyRow(UUID id) {
    return securityRepo.findCompanyRow(id);
  }

  public Data<Map<String,Object>> company(UUID id) {
    var comp = new LinkedHashMap<>(companyRow(id));
    comp.put("securities", securityRepo.findCompanySecurities(id));
    return data(comp, "Company profile and listing metadata may be incomplete.");
  }

  public Page<Map<String,Object>> companies(String search,boolean activeOnly,int page,int size) {
    return securityRepo.queryCompanies(search, activeOnly, page, size);
  }

  // ---- data status ----
  public Data<Map<String,Object>> status(String symbol,String exchange) {
    var security = read.resolve(symbol, exchange);
    var args = Map.<String,Object>of(
        "security", UUID.fromString(security.get("id").toString()),
        "company", UUID.fromString(security.get("companyId").toString()));
    var prices = securityRepo.findPriceCoverage(args);
    var reports = securityRepo.findReportCoverage(args);
    var metrics = securityRepo.findMetricCoverage(args);
    var newsCoverage = securityRepo.findNewsCoverage(args);
    var limitations = new ArrayList<String>();
    if (count(prices, "count") == 0) limitations.add("NO_PUBLISHED_DAILY_PRICES");
    if (count(reports, "count") == 0) limitations.add("NO_PUBLISHED_STATEMENTS");
    if (count(metrics, "count") == 0) limitations.add("NO_PUBLISHED_METRICS");
    if (count(reports, "missingPublishedAt") > 0) limitations.add("PUBLICATION_DATES_INCOMPLETE");
    if (count(reports, "unknownScope") > 0) limitations.add("REPORT_SCOPE_INCOMPLETE");
    if (count(reports, "unknownPeriodBasis") > 0) limitations.add("PERIOD_BASIS_INCOMPLETE");
    var result = new LinkedHashMap<String,Object>();
    result.put("security", security);
    result.put("prices", prices);
    result.put("statements", reports);
    result.put("metrics", metrics);
    result.put("news", newsCoverage);
    result.put("availability", limitations.isEmpty() ? "AVAILABLE_WITH_UNVERIFIED_HISTORY" : "PARTIAL");
    result.put("limitations", limitations);
    result.put("pointInTimeCertified", false);
    return data(result,
        "Coverage counts current published records, not expected trading sessions or complete financial history. No zero-issues => healthy inference.",
        "Timestamps retain their meaning: price event, report period, publication, crawl and storage are separate.");
  }

  private long count(Map<String,Object> row, String key) {
    return Long.parseLong(row.get(key).toString());
  }

  // ---- overview: longer read snapshot (timeout 30) shared by every composed section ----
  @Transactional(readOnly=true,timeout=30,isolation=Isolation.REPEATABLE_READ)
  public Data<Map<String,Object>> availability() {
    var local = new LinkedHashMap<String,Object>();
    boolean registry = securityRepo.hasModelVersionsColumns();
    local.put("registryAvailable", registry);
    local.put("publicPredictionReader", "NOT_INTEGRATED");
    local.put("reason", "PUBLICATION_POLICY_REQUIRED");
    var regEntries = registry ? securityRepo.queryModelVersionRegistry() : List.<Map<String,Object>>of();
    if (regEntries.size() > 100) {
      local.put("registry", List.copyOf(regEntries.subList(0, 100)));
      local.put("registryTruncated", true);
    } else {
      local.put("registry", regEntries);
      local.put("registryTruncated", false);
    }
    local.put("registeredSuite", localAi.summary());
    var result = new LinkedHashMap<String,Object>();
    result.put("localAi", local);
    result.put("newsAnalysis", Map.of("readerAvailable", true, "publicationGuard", "CURRENT_SOURCE_AND_COMPLETE_VALIDATION", "route", "/api/v1/news/{id}/analysis"));
    result.put("financialScenarios", Map.of("readerAvailable", true, "publicationGuard", "CURRENT_SOURCE_AND_COMPLETE_VALIDATION", "route", "/api/v1/securities/{symbol}/forecasts"));
    result.put("executesModels", false);
    return data(result,
        "Registry state (including ACTIVE) does not certify public prediction eligibility. Local AI research artifacts, retrospective outcomes and unapproved disclosure facts are not published by this API.",
        "Availability describes readers in this build, not model accuracy or a guarantee that any eligible result exists. Native NEWS/forecast publication guards remain authoritative.");
  }

  @Transactional(readOnly=true,timeout=30,isolation=Isolation.REPEATABLE_READ)
  public Data<Map<String,Object>> overview(String symbol,String exchange) {
    var profile = detail(symbol, exchange);
    String resolvedExchange = profile.data().get("exchange").toString();
    var forecasts = news.forecasts(symbol, resolvedExchange, 3);
    var result = new LinkedHashMap<String,Object>();
    result.put("profile", profile);
    result.put("quote", market.latest(symbol, resolvedExchange));
    result.put("dataStatus", status(symbol, resolvedExchange));
    result.put("statements", financial.statements(symbol, resolvedExchange, null, null, null, null, 0, 3));
    result.put("metrics", financial.metrics(symbol, resolvedExchange, null, null, null, null, null, 0, 10));
    result.put("news", news.news(symbol, resolvedExchange, null, null, null, 0, 3));
    result.put("financialScenarios", forecasts);
    result.put("financialScenarioState", forecasts.data().isEmpty() ? "NO_ELIGIBLE_RESULT" : "AVAILABLE");
    result.put("analysisAvailability", availability());
    result.put("localAi", localAi.security(symbol, resolvedExchange, null));
    return data(result,
        "Bounded overview: 3 statements, 10 metric observations, 3 news and at most 3 current scenario candidates. Follow each paginated resource for history; metric observations are not one latest value per code.",
        "All sections share the read transaction snapshot. No eligible scenario can mean absent, outdated or blocked by publication guards; reads do not repair or execute it.");
  }
}
