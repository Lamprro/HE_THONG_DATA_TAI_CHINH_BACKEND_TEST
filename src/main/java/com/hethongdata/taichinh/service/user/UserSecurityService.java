package com.hethongdata.taichinh.service.user;

import java.util.*;
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
  private final UserReadSupport read;private final UserMarketService market;private final UserFinancialService financial;
  private final UserNewsService news;private final UserLocalAiService localAi;
  public UserSecurityService(UserReadSupport read,UserMarketService market,UserFinancialService financial,UserNewsService news,UserLocalAiService localAi) {
    this.read=read;this.market=market;this.financial=financial;this.news=news;this.localAi=localAi;
  }

  // ---- master data ----
  public Page<Map<String,Object>> securities(String search,String exchange,boolean activeOnly,int page,int size) {
    var args=new HashMap<String,Object>();args.put("search",search(search));args.put("activeOnly",activeOnly);
    String where=SECURITY_JOIN+"where (:activeOnly=false or (s.is_active and c.is_active)) and (s.symbol ilike :search or c.legal_name ilike :search or c.short_name ilike :search)";
    if(exchange!=null&&!exchange.isBlank()) {args.put("exchange",code(exchange));where+=" and s.exchange=:exchange";}
    return read.page("select "+SECURITY_FIELDS,where,"order by s.symbol,s.exchange,s.id",args,page,size,100,"Stored master data; listing and shares may be incomplete or historical.");
  }
  public Data<Map<String,Object>> detail(String symbol,String exchange) {
    var security=new LinkedHashMap<>(read.resolve(symbol,exchange));
    security.put("company",companyRow(UUID.fromString(security.get("companyId").toString())));
    return data(security,"Master profile is a stored snapshot, not a live provider request.");
  }
  private Map<String,Object> companyRow(UUID id) {
    return read.one("""
      select id,company_code as "companyCode",legal_name as "legalName",short_name as "shortName",
      english_name as "englishName",industry_code as "industryCode",industry_name as "industryName",sector_name as "sectorName",
      website,headquarters,founded_date as "foundedDate",listing_status as "listingStatus",description,is_active as "active",updated_at as "updatedAt"
      from companies where id=:id
      """,Map.of("id",id),"Company");
  }
  public Data<Map<String,Object>> company(UUID id) {
    var company=new LinkedHashMap<>(companyRow(id));
    company.put("securities",read.rows("select "+SECURITY_FIELDS+SECURITY_JOIN+"where c.id=:id order by s.symbol,s.exchange,s.id",Map.of("id",id)));
    return data(company,"Company profile and listing metadata may be incomplete.");
  }
  public Page<Map<String,Object>> companies(String search,boolean activeOnly,int page,int size) {
    return read.page("select c.id,c.company_code as \"companyCode\",c.legal_name as \"legalName\",c.short_name as \"shortName\",c.industry_name as \"industryName\",c.sector_name as \"sectorName\",c.is_active as active",
      "from companies c where (:activeOnly=false or c.is_active) and (c.legal_name ilike :search or c.short_name ilike :search or c.company_code ilike :search)",
      "order by c.legal_name,c.id",Map.of("search",search(search),"activeOnly",activeOnly),page,size,100,"Company UUIDs are distinct from security UUIDs. One company can have multiple listings.");
  }

  // ---- data status ----
  public Data<Map<String,Object>> status(String symbol,String exchange) {
    var security=read.resolve(symbol,exchange);var args=Map.<String,Object>of("security",UUID.fromString(security.get("id").toString()),"company",UUID.fromString(security.get("companyId").toString()));
    var prices=read.one("""
      select count(*) as count,min(p.price_timestamp) as "firstTimestamp",max(p.price_timestamp) as "lastTimestamp",max(p.updated_at) as "lastStoredAt"
      from market_prices p join data_versions v on v.id=p.data_version_id and v.status='ACTIVATED'
      where p.security_id=:security and p.is_canonical and p.interval_code='1d'
      """,args,"Price coverage");
    var reports=read.one("""
      select count(*) as count,min(p.end_date) as "firstPeriodEnd",max(p.end_date) as "lastPeriodEnd",
      count(*) filter(where f.published_at is null) as "missingPublishedAt",
      count(*) filter(where f.report_scope is null or f.report_scope='UNKNOWN') as "unknownScope",
      count(*) filter(where f.period_basis is null or f.period_basis='UNKNOWN') as "unknownPeriodBasis",max(f.created_at) as "lastStoredAt"
      """+UserFinancialService.STATEMENTS,args,"Report coverage");
    var metrics=read.one("""
      select count(*) as count,min(m.as_of_date) as "firstAsOfDate",max(m.as_of_date) as "lastAsOfDate",max(m.created_at) as "lastStoredAt"
      from financial_metrics m where m.company_id=:company and m.is_canonical and m.quality_status in ('VALID','WARNING')
      and ((m.data_version_id is null and m.is_derived) or exists(select 1 from data_versions v where v.id=m.data_version_id and v.status='ACTIVATED'))
      """,args,"Metric coverage");
    var news=read.one("select count(*) as count,max(n.published_at) as \"lastPublishedAt\",max(n.crawled_at) as \"lastCrawledAt\" "+UserNewsService.VISIBLE+
        " and exists(select 1 from news_article_companies x where x.news_article_id=n.id and (x.security_id=:security or (x.security_id is null and x.company_id=:company)))",args,"News coverage");
    var limitations=new ArrayList<String>();
    if(count(prices,"count")==0) limitations.add("NO_PUBLISHED_DAILY_PRICES");
    if(count(reports,"count")==0) limitations.add("NO_PUBLISHED_STATEMENTS");
    if(count(metrics,"count")==0) limitations.add("NO_PUBLISHED_METRICS");
    if(count(reports,"missingPublishedAt")>0) limitations.add("PUBLICATION_DATES_INCOMPLETE");
    if(count(reports,"unknownScope")>0) limitations.add("REPORT_SCOPE_INCOMPLETE");
    if(count(reports,"unknownPeriodBasis")>0) limitations.add("PERIOD_BASIS_INCOMPLETE");
    var result=new LinkedHashMap<String,Object>();result.put("security",security);result.put("prices",prices);result.put("statements",reports);result.put("metrics",metrics);result.put("news",news);
    result.put("availability",limitations.isEmpty()?"AVAILABLE_WITH_UNVERIFIED_HISTORY":"PARTIAL");result.put("limitations",limitations);result.put("pointInTimeCertified",false);
    return data(result,"Coverage counts current published records, not expected trading sessions or complete financial history. No zero-issues => healthy inference.","Timestamps retain their meaning: price event, report period, publication, crawl and storage are separate.");
  }
  private long count(Map<String,Object> row,String key) {return Long.parseLong(row.get(key).toString());}

  // ---- overview: longer read snapshot (timeout 30) shared by every composed section ----
  @Transactional(readOnly=true,timeout=30,isolation=Isolation.REPEATABLE_READ)
  public Data<Map<String,Object>> availability(){
    var local=new LinkedHashMap<String,Object>();boolean registry=read.hasColumns("model_versions",Set.of("target_name","status","trained_at"));
    local.put("registryAvailable",registry);local.put("publicPredictionReader","NOT_INTEGRATED");local.put("reason","PUBLICATION_POLICY_REQUIRED");
    local.put("registry",registry?read.rows("select target_name as target,status,count(*) as count,max(trained_at) as \"lastTrainedAt\" from model_versions group by target_name,status order by target_name,status limit 101",Map.of()):List.of());
    @SuppressWarnings("unchecked") var entries=(List<Map<String,Object>>)local.get("registry");
    if(entries.size()>100){local.put("registry",List.copyOf(entries.subList(0,100)));local.put("registryTruncated",true);}else local.put("registryTruncated",false);
    local.put("registeredSuite",localAi.summary());
    var result=new LinkedHashMap<String,Object>();result.put("localAi",local);
    result.put("newsAnalysis",Map.of("readerAvailable",true,"publicationGuard","CURRENT_SOURCE_AND_COMPLETE_VALIDATION","route","/api/v1/news/{id}/analysis"));
    result.put("financialScenarios",Map.of("readerAvailable",true,"publicationGuard","CURRENT_SOURCE_AND_COMPLETE_VALIDATION","route","/api/v1/securities/{symbol}/forecasts"));
    result.put("executesModels",false);return data(result,"Registry state (including ACTIVE) does not certify public prediction eligibility. Local AI research artifacts, retrospective outcomes and unapproved disclosure facts are not published by this API.","Availability describes readers in this build, not model accuracy or a guarantee that any eligible result exists. Native NEWS/forecast publication guards remain authoritative.");
  }
  @Transactional(readOnly=true,timeout=30,isolation=Isolation.REPEATABLE_READ)
  public Data<Map<String,Object>> overview(String symbol,String exchange){
    var profile=detail(symbol,exchange);String resolvedExchange=profile.data().get("exchange").toString();
    var forecasts=news.forecasts(symbol,resolvedExchange,3);var result=new LinkedHashMap<String,Object>();
    result.put("profile",profile);result.put("quote",market.latest(symbol,resolvedExchange));result.put("dataStatus",status(symbol,resolvedExchange));
    result.put("statements",financial.statements(symbol,resolvedExchange,null,null,null,null,0,3));
    result.put("metrics",financial.metrics(symbol,resolvedExchange,null,null,null,null,null,0,10));
    result.put("news",news.news(symbol,resolvedExchange,null,null,null,0,3));result.put("financialScenarios",forecasts);
    result.put("financialScenarioState",forecasts.data().isEmpty()?"NO_ELIGIBLE_RESULT":"AVAILABLE");result.put("analysisAvailability",availability());
    result.put("localAi",localAi.security(symbol,resolvedExchange,null));
    return data(result,"Bounded overview: 3 statements, 10 metric observations, 3 news and at most 3 current scenario candidates. Follow each paginated resource for history; metric observations are not one latest value per code.","All sections share the read transaction snapshot. No eligible scenario can mean absent, outdated or blocked by publication guards; reads do not repair or execute it.");
  }
}
