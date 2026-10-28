package com.hethongdata.taichinh.service.user;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;

@Service @Transactional(readOnly=true,timeout=15,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class UserNewsService {
  private final UserReadSupport read;private final UserSecurityService securities;
  public UserNewsService(UserReadSupport read,UserSecurityService securities) {this.read=read;this.securities=securities;}
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
  public Page<Map<String,Object>> news(String symbol,String exchange,String search,LocalDate from,LocalDate to,int page,int size) {
    var args=new HashMap<String,Object>();String where=VISIBLE;args.put("search",search(search));where+=" and (n.title ilike :search or n.sapo ilike :search)";
    if(symbol!=null&&!symbol.isBlank()) {
      var security=securities.resolve(symbol,exchange);args.put("security",UUID.fromString(security.get("id").toString()));args.put("company",UUID.fromString(security.get("companyId").toString()));
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
}
