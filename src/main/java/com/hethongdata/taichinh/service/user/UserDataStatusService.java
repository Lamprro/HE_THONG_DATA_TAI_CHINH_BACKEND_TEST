package com.hethongdata.taichinh.service.user;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;

@Service
@Transactional(readOnly=true,timeout=15,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class UserDataStatusService {
  private final UserReadSupport read;private final UserSecurityService securities;private final UserFinancialService financial;
  public UserDataStatusService(UserReadSupport read,UserSecurityService securities,UserFinancialService financial) {this.read=read;this.securities=securities;this.financial=financial;}
  public Data<Map<String,Object>> status(String symbol,String exchange) {
    var security=securities.resolve(symbol,exchange);var args=Map.<String,Object>of("security",UUID.fromString(security.get("id").toString()),"company",UUID.fromString(security.get("companyId").toString()));
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
  public Data<List<Map<String,Object>>> periods(String symbol,String exchange) {
    var company=UUID.fromString(securities.resolve(symbol,exchange).get("companyId").toString());
    var rows=read.rows("select p.id,p.fiscal_year as \"fiscalYear\",p.period_type as \"periodType\",p.start_date as \"periodStart\",p.end_date as \"periodEnd\",count(*) as \"reportCount\" "+UserFinancialService.STATEMENTS+
        " group by p.id,p.fiscal_year,p.period_type,p.start_date,p.end_date order by p.end_date desc,p.period_type,p.id limit 501",Map.of("company",company));
    if(rows.size()>500) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"More than 500 reporting periods; use paginated statements");
    return data(rows,"Only periods with current canonical activated reports. A period end is not a filing date.");
  }
  public Data<Map<String,Object>> lineage(String symbol,String exchange,UUID report) {
    financial.publishedReport(symbol,exchange,report); // Same visibility/ownership gate, without loading report items.
    var result=read.one("""
      select f.id as "statementId",f.data_version_id as "versionId",v.status as "versionStatus",v.activated_at as "activatedAt",
      d.code as "sourceCode",d.name as "sourceName",r.fetched_at as "sourceFetchedAt",
      case when r.source_url ~* '^https?://' then r.source_url end as "sourceUrl",
      f.published_at as "publishedAt",f.report_scope as "reportScope",f.period_basis as "periodBasis",f.created_at as "storedAt"
      from financial_statements f join data_sources d on d.id=f.data_source_id join data_versions v on v.id=f.data_version_id
      left join raw_payloads r on r.id=f.raw_payload_id where f.id=:id
      """,Map.of("id",report),"Statement source");
    result.put("sourceUrl",publicSourceUrl(result.get("sourceUrl")));
    return data(result,"Public lineage projection only; raw bodies, headers and ingestion parameters are omitted. Source links with credentials, queries or fragments are not exposed.");
  }
  public Data<Map<String,Object>> filters() {
    var result=new LinkedHashMap<String,Object>();
    result.put("exchanges",read.rows("select s.exchange,count(*) as count "+UserSecurityService.JOIN+" where s.is_active and c.is_active group by s.exchange order by s.exchange",Map.of()));
    result.put("industries",read.rows("select c.industry_name as name,count(distinct c.id) as count "+UserSecurityService.JOIN+" where s.is_active and c.is_active and c.industry_name is not null group by c.industry_name order by c.industry_name",Map.of()));
    result.put("statementTypes",List.of("BALANCE_SHEET","INCOME_STATEMENT","CASH_FLOW"));result.put("periodTypes",List.of("FY","Q1","Q2","Q3","Q4"));
    result.put("metricOrigins",List.of("PROVIDER","DERIVED"));return data(result,"Filter counts describe tracked active master records, not the whole exchange.");
  }
}
