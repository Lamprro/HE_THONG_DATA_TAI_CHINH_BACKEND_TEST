package com.hethongdata.taichinh.repository.jdbc.user;

import com.hethongdata.taichinh.service.user.UserReadSupport;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class UserSecurityQueryRepository {
  public static final String STATEMENTS = """
    from financial_statements f join financial_periods p on p.id=f.financial_period_id
    join data_sources d on d.id=f.data_source_id join data_versions v on v.id=f.data_version_id and v.status='ACTIVATED'
    where f.company_id=:company and f.is_current and f.is_canonical
    """;

  private final UserReadJdbcRepository read;

  public UserSecurityQueryRepository(UserReadJdbcRepository read) {
    this.read = read;
  }

  public UserReadSupport.Page<Map<String, Object>> querySecurities(
      String search, String exchange, boolean activeOnly, int page, int size) {
    var args = new HashMap<String, Object>();
    args.put("search", UserReadSupport.search(search));
    args.put("activeOnly", activeOnly);
    String where = UserReadJdbcRepository.SECURITY_JOIN
        + "where (:activeOnly=false or (s.is_active and c.is_active)) and (s.symbol ilike :search or c.legal_name ilike :search or c.short_name ilike :search)";
    if (exchange != null && !exchange.isBlank()) {
      args.put("exchange", UserReadSupport.code(exchange));
      where += " and s.exchange=:exchange";
    }
    return read.page(
        "select " + UserReadJdbcRepository.SECURITY_FIELDS, where, "order by s.symbol,s.exchange,s.id",
        args, page, size, 100, "Stored master data; listing and shares may be incomplete or historical.");
  }

  public Map<String, Object> findCompanyRow(UUID id) {
    return read.one(
        """
        select id,company_code as "companyCode",legal_name as "legalName",short_name as "shortName",
        english_name as "englishName",industry_code as "industryCode",industry_name as "industryName",sector_name as "sectorName",
        website,headquarters,founded_date as "foundedDate",listing_status as "listingStatus",description,is_active as "active",updated_at as "updatedAt"
        from companies where id=:id
        """,
        Map.of("id", id), "Company");
  }

  public List<Map<String, Object>> findCompanySecurities(UUID companyId) {
    return read.rows(
        "select " + UserReadJdbcRepository.SECURITY_FIELDS + UserReadJdbcRepository.SECURITY_JOIN + "where c.id=:id order by s.symbol,s.exchange,s.id",
        Map.of("id", companyId));
  }

  public UserReadSupport.Page<Map<String, Object>> queryCompanies(
      String search, boolean activeOnly, int page, int size) {
    return read.page(
        "select c.id,c.company_code as \"companyCode\",c.legal_name as \"legalName\",c.short_name as \"shortName\",c.industry_name as \"industryName\",c.sector_name as \"sectorName\",c.is_active as active",
        "from companies c where (:activeOnly=false or c.is_active) and (c.legal_name ilike :search or c.short_name ilike :search or c.company_code ilike :search)",
        "order by c.legal_name,c.id", Map.of("search", UserReadSupport.search(search), "activeOnly", activeOnly), page, size, 100,
        "Company UUIDs are distinct from security UUIDs. One company can have multiple listings.");
  }

  public Map<String, Object> findPriceCoverage(Map<String, Object> args) {
    return read.one(
        """
        select count(*) as count,min(p.price_timestamp) as "firstTimestamp",max(p.price_timestamp) as "lastTimestamp",max(p.updated_at) as "lastStoredAt"
        from market_prices p join data_versions v on v.id=p.data_version_id and v.status='ACTIVATED'
        where p.security_id=:security and p.is_canonical and p.interval_code='1d'
        """,
        args, "Price coverage");
  }

  public Map<String, Object> findReportCoverage(Map<String, Object> args) {
    return read.one(
        """
        select count(*) as count,min(p.end_date) as "firstPeriodEnd",max(p.end_date) as "lastPeriodEnd",
        count(*) filter(where f.published_at is null) as "missingPublishedAt",
        count(*) filter(where f.report_scope is null or f.report_scope='UNKNOWN') as "unknownScope",
        count(*) filter(where f.period_basis is null or f.period_basis='UNKNOWN') as "unknownPeriodBasis",max(f.created_at) as "lastStoredAt"
        """ + STATEMENTS,
        args, "Report coverage");
  }

  public Map<String, Object> findMetricCoverage(Map<String, Object> args) {
    return read.one(
        """
        select count(*) as count,min(m.as_of_date) as "firstAsOfDate",max(m.as_of_date) as "lastAsOfDate",max(m.created_at) as "lastStoredAt"
        from financial_metrics m where m.company_id=:company and m.is_canonical and m.quality_status in ('VALID','WARNING')
        and ((m.data_version_id is null and m.is_derived) or exists(select 1 from data_versions v where v.id=m.data_version_id and v.status='ACTIVATED'))
        """,
        args, "Metric coverage");
  }

  public Map<String, Object> findNewsCoverage(Map<String, Object> args) {
    return read.one(
        "select count(*) as count,max(n.published_at) as \"lastPublishedAt\",max(n.crawled_at) as \"lastCrawledAt\" "
            + UserNewsQueryRepository.VISIBLE
            + " and exists(select 1 from news_article_companies x where x.news_article_id=n.id and (x.security_id=:security or (x.security_id is null and x.company_id=:company)))",
        args, "News coverage");
  }
}
