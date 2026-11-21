package com.hethongdata.taichinh.repository.jdbc.user;

import com.hethongdata.taichinh.service.user.UserReadSupport;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class UserNewsQueryRepository {
  public static final String VISIBLE = """
    from news_articles n join data_sources d on d.id=n.data_source_id
    where n.duplicate_of_news_article_id is null and n.dedup_status='UNIQUE' and not n.is_deleted_source
    and coalesce(n.metadata->>'is_test','false')<>'true'
    and exists(select 1 from raw_payloads r join data_versions v on v.ingestion_run_id=r.ingestion_run_id and v.status='ACTIVATED' where r.id=n.raw_payload_id)
    """;

  public static final String FIELDS = """
    n.id,n.title,n.sapo,case when n.canonical_url ~* '^https?://' then n.canonical_url end as "url",n.author,n.language,
    n.published_at as "publishedAt",n.crawled_at as "crawledAt",d.code as "sourceCode",d.name as "sourceName",
    (n.content_text is not null and length(trim(n.content_text))>0) as "hasContent"
    """;

  private final UserReadJdbcRepository read;

  public UserNewsQueryRepository(UserReadJdbcRepository read) {
    this.read = read;
  }

  public UserReadSupport.Page<Map<String, Object>> queryNews(
      String symbol, String exchange, String search, LocalDate from, LocalDate to, int page, int size) {
    var args = new HashMap<String, Object>();
    String where = VISIBLE;
    args.put("search", UserReadSupport.search(search));
    where += " and (n.title ilike :search or n.sapo ilike :search)";
    if (symbol != null && !symbol.isBlank()) {
      var security = read.resolve(symbol, exchange);
      args.put("security", UUID.fromString(security.get("id").toString()));
      args.put("company", UUID.fromString(security.get("companyId").toString()));
      where += " and exists(select 1 from news_article_companies x where x.news_article_id=n.id and (x.security_id=:security or (x.security_id is null and x.company_id=:company)))";
    } else if (exchange != null && !exchange.isBlank()) {
      throw new IllegalArgumentException("exchange requires a symbol for news filtering");
    }
    if (from != null || to != null) {
      var range = UserReadSupport.window(from, to, 1, 30);
      args.put("start", range.start());
      args.put("until", range.until());
      where += " and n.published_at>=:start and n.published_at<:until";
    }
    return read.page(
        "select " + FIELDS, where, "order by n.published_at desc nulls last,n.crawled_at desc,n.id",
        args, page, size, 100,
        "Only unique non-deleted stored articles from activated source batches. Date filters use publishedAt; unknown publication dates are excluded only when a date filter is used.",
        "A URL-only article remains readable as a link. publishedAt and crawledAt are different; missing body is not reconstructed.");
  }

  public Map<String, Object> findVisibleArticle(UUID id) {
    return read.one("select " + FIELDS + VISIBLE + " and n.id=:id", Map.of("id", id), "Published news article");
  }

  public Map<String, Object> findArticleContent(UUID id) {
    return read.one(
        "select substring(content_text from 1 for 100000) as \"contentText\",coalesce(length(content_text)>100000,false) as \"contentTruncated\" from news_articles where id=:id",
        Map.of("id", id), "Article");
  }

  public List<Map<String, Object>> findArticleCompanies(UUID id) {
    return read.rows(
        """
        select c.id as "companyId",c.legal_name as "companyName",s.symbol,s.exchange,x.match_method as "matchMethod",
        case when lower(x.match_evidence->>'kind')='source_job' then 'SOURCE_JOB' else x.match_method end as "associationType"
        from news_article_companies x join companies c on c.id=x.company_id left join securities s on s.id=x.security_id
        where x.news_article_id=:id order by c.legal_name,s.symbol,s.exchange,x.id limit 101
        """,
        Map.of("id", id));
  }
}
