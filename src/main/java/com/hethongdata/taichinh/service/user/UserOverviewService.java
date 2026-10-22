package com.hethongdata.taichinh.service.user;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;

/** Composes existing readers; never schedules ingestion, trains or invokes a model. */
@Service @Transactional(readOnly=true,timeout=30,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class UserOverviewService {
  private final UserReadSupport read;private final UserSecurityService securities;private final UserMarketService market;
  private final UserFinancialService financial;private final UserNewsService news;private final UserAnalysisService analysis;private final UserDataStatusService status;
  public UserOverviewService(UserReadSupport read,UserSecurityService securities,UserMarketService market,UserFinancialService financial,UserNewsService news,UserAnalysisService analysis,UserDataStatusService status){
    this.read=read;this.securities=securities;this.market=market;this.financial=financial;this.news=news;this.analysis=analysis;this.status=status;
  }
  public Data<Map<String,Object>> availability(){
    var local=new LinkedHashMap<String,Object>();boolean registry=read.hasColumns("model_versions",Set.of("target_name","status","trained_at"));
    local.put("registryAvailable",registry);local.put("publicPredictionReader","NOT_INTEGRATED");local.put("reason","PUBLICATION_POLICY_REQUIRED");
    local.put("registry",registry?read.rows("select target_name as target,status,count(*) as count,max(trained_at) as \"lastTrainedAt\" from model_versions group by target_name,status order by target_name,status limit 101",Map.of()):List.of());
    @SuppressWarnings("unchecked") var entries=(List<Map<String,Object>>)local.get("registry");
    if(entries.size()>100){local.put("registry",List.copyOf(entries.subList(0,100)));local.put("registryTruncated",true);}else local.put("registryTruncated",false);
    var result=new LinkedHashMap<String,Object>();result.put("localAi",local);
    result.put("newsAnalysis",Map.of("readerAvailable",true,"publicationGuard","CURRENT_SOURCE_AND_COMPLETE_VALIDATION","route","/api/v1/news/{id}/analysis"));
    result.put("financialScenarios",Map.of("readerAvailable",true,"publicationGuard","CURRENT_SOURCE_AND_COMPLETE_VALIDATION","route","/api/v1/securities/{symbol}/forecasts"));
    result.put("executesModels",false);return data(result,"Registry state (including ACTIVE) does not certify public prediction eligibility. Local AI research artifacts, retrospective outcomes and unapproved disclosure facts are not published by this API.","Availability describes readers in this build, not model accuracy or a guarantee that any eligible result exists. Native NEWS/forecast publication guards remain authoritative.");
  }
  public Data<Map<String,Object>> overview(String symbol,String exchange){
    var profile=securities.detail(symbol,exchange);String resolvedExchange=profile.data().get("exchange").toString();
    var forecasts=analysis.forecasts(symbol,resolvedExchange,3);var result=new LinkedHashMap<String,Object>();
    result.put("profile",profile);result.put("quote",market.latest(symbol,resolvedExchange));result.put("dataStatus",status.status(symbol,resolvedExchange));
    result.put("statements",financial.statements(symbol,resolvedExchange,null,null,null,null,0,3));
    result.put("metrics",financial.metrics(symbol,resolvedExchange,null,null,null,null,null,0,10));
    result.put("news",news.news(symbol,resolvedExchange,null,null,null,0,3));result.put("financialScenarios",forecasts);
    result.put("financialScenarioState",forecasts.data().isEmpty()?"NO_ELIGIBLE_RESULT":"AVAILABLE");result.put("analysisAvailability",availability());
    return data(result,"Bounded overview: 3 statements, 10 metric observations, 3 news and at most 3 current scenario candidates. Follow each paginated resource for history; metric observations are not one latest value per code.","All sections share the read transaction snapshot. No eligible scenario can mean absent, outdated or blocked by publication guards; reads do not repair or execute it.");
  }
}
