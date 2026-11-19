package com.hethongdata.taichinh.service.user;

import com.fasterxml.jackson.databind.JsonNode;
import com.hethongdata.taichinh.service.forecast.ForecastRunStore;
import com.hethongdata.taichinh.service.llm.LlmRunStore;
import java.time.*;
import java.util.*;
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
  private final UserReadSupport read;private final LlmRunStore newsResults;private final ForecastRunStore forecasts;
  public UserNewsService(UserReadSupport read,LlmRunStore newsResults,ForecastRunStore forecasts) {this.read=read;this.newsResults=newsResults;this.forecasts=forecasts;}
  static final String VISIBLE="""
    from news_articles n join data_sources d on d.id=n.data_source_id
    where n.duplicate_of_news_article_id is null and n.dedup_status='UNIQUE' and not n.is_deleted_source
    and coalesce(n.metadata->>'is_test','false')<>'true'
    and exists(select 1 from raw_payloads r join data_versions v on v.ingestion_run_id=r.ingestion_run_id and v.status='ACTIVATED' where r.id=n.raw_payload_id)
    """;
  static final String FIELDS="""
    n.id,n.title,n.sapo,case when n.canonical_url ~* '^https?://' then n.canonical_url end as "url",n.author,n.language,
    n.published_at as "publishedAt",n.crawled_at as "crawledAt",d.code as "sourceCode",d.name as "sourceName",
    (n.content_text is not null and length(trim(n.content_text))>0) as "hasContent"
    """;

  // ---- news ----
  public Page<Map<String,Object>> news(String symbol,String exchange,String search,LocalDate from,LocalDate to,int page,int size) {
    var args=new HashMap<String,Object>();String where=VISIBLE;args.put("search",search(search));where+=" and (n.title ilike :search or n.sapo ilike :search)";
    if(symbol!=null&&!symbol.isBlank()) {
      var security=read.resolve(symbol,exchange);args.put("security",UUID.fromString(security.get("id").toString()));args.put("company",UUID.fromString(security.get("companyId").toString()));
      where+=" and exists(select 1 from news_article_companies x where x.news_article_id=n.id and (x.security_id=:security or (x.security_id is null and x.company_id=:company)))";
    } else if(exchange!=null&&!exchange.isBlank()) throw new IllegalArgumentException("exchange requires a symbol for news filtering");
    if(from!=null||to!=null) {var range=window(from,to,1,30);args.put("start",range.start());args.put("until",range.until());where+=" and n.published_at>=:start and n.published_at<:until";}
    return read.page("select "+FIELDS,where,"order by n.published_at desc nulls last,n.crawled_at desc,n.id",args,page,size,100,
      "Only unique non-deleted stored articles from activated source batches. Date filters use publishedAt; unknown publication dates are excluded only when a date filter is used.",
      "A URL-only article remains readable as a link. publishedAt and crawledAt are different; missing body is not reconstructed.");
  }
  public Map<String,Object> visible(UUID id) {return read.one("select "+FIELDS+VISIBLE+" and n.id=:id",Map.of("id",id),"Published news article");}
  public Data<Map<String,Object>> detail(UUID id) {
    var article=new LinkedHashMap<>(visible(id));
    article.putAll(read.one("select substring(content_text from 1 for 100000) as \"contentText\",coalesce(length(content_text)>100000,false) as \"contentTruncated\" from news_articles where id=:id",Map.of("id",id),"Article"));
    article.put("companies",read.rows("""
      select c.id as "companyId",c.legal_name as "companyName",s.symbol,s.exchange,x.match_method as "matchMethod",
      case when lower(x.match_evidence->>'kind')='source_job' then 'SOURCE_JOB' else x.match_method end as "associationType"
      from news_article_companies x join companies c on c.id=x.company_id left join securities s on s.id=x.security_id
      where x.news_article_id=:id order by c.legal_name,s.symbol,s.exchange,x.id limit 101
      """,Map.of("id",id)));
    @SuppressWarnings("unchecked") var links=(List<Map<String,Object>>)article.get("companies");
    article.put("companyLinksTruncated",links.size()>100);if(links.size()>100) article.put("companies",links.subList(0,100));
    return data(article,"Content is stored plain text, not HTML. Null body means unavailable; use the source URL.","SOURCE_JOB is a source association, not proof that the body mentions that symbol. Relation matchMethod remains explicit.");
  }

  // ---- published AI analysis (timeout 20) ----
  @Transactional(readOnly=true,timeout=20,isolation=Isolation.REPEATABLE_READ)
  public Data<List<Map<String,Object>>> analysis(UUID id) {
    visible(id);var results=new ArrayList<Map<String,Object>>();
    for(JsonNode result:newsResults.results(id)) {
      if(!Set.of("VALID","WARNING").contains(result.path("quality_status").asText())) continue;
      var row=new LinkedHashMap<String,Object>();row.put("id",result.path("id").asText());row.put("task",result.path("task_code").asText());row.put("qualityStatus",result.path("quality_status").asText());
      row.put("createdAt",result.path("created_at").asText());row.put("data",read.publicJson(result.path("data")));results.add(row);
    }
    return data(List.copyOf(results),"Published current results only: existing source-hash, enabled-template, validation-round and policy guards apply. Empty results do not trigger AI execution.","Quality describes validation of the response, not independently verified financial facts or prediction accuracy.");
  }
  @Transactional(readOnly=true,timeout=20,isolation=Isolation.REPEATABLE_READ)
  public Data<List<Map<String,Object>>> forecasts(String symbol,String exchange,int limit) {
    if(limit<1||limit>20) throw new IllegalArgumentException("limit must be 1..20");
    var security=read.resolve(symbol,exchange);UUID id=UUID.fromString(security.get("id").toString());var results=new ArrayList<Map<String,Object>>();
    for(var result:forecasts.results(id,limit)) {
      if(!Set.of("VALID","WARNING").contains(result.qualityStatus())) continue;
      var row=new LinkedHashMap<String,Object>();row.put("id",result.id());row.put("symbol",security.get("symbol"));row.put("exchange",security.get("exchange"));row.put("securityId",id);
      row.put("asOfDate",result.asOfDate());row.put("forecastPeriodStart",result.forecastPeriodStart());row.put("forecastPeriodEnd",result.forecastPeriodEnd());row.put("targetScope",result.targetScope());row.put("qualityStatus",result.qualityStatus());row.put("data",read.publicJson(result.data()));results.add(row);
    }
    return data(List.copyOf(results),"Existing publication reader checks current source snapshot, validation policy and the complete blocking-validation round. At most limit current candidates are examined; this is not a paginated history.","These are AI scenarios with evidence and limitations, not certified forecasts, calibrated probabilities, target prices or local-AI deployment results. Reads never execute a model.");
  }
}
