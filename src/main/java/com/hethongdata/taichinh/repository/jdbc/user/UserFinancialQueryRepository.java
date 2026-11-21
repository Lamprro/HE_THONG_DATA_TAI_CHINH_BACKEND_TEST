package com.hethongdata.taichinh.repository.jdbc.user;

import com.hethongdata.taichinh.service.user.UserReadSupport;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class UserFinancialQueryRepository {
  public static final String STATEMENTS = """
    from financial_statements f join financial_periods p on p.id=f.financial_period_id
    join data_sources d on d.id=f.data_source_id join data_versions v on v.id=f.data_version_id and v.status='ACTIVATED'
    where f.company_id=:company and f.is_current and f.is_canonical
    """;

  public static final String FIELDS = """
    f.id,f.company_id as "companyId",f.statement_type as "statementType",f.report_scope as "reportScope",f.period_basis as "periodBasis",
    f.currency,f.unit_scale as "unitScale",f.audited,f.revision_no as "revision",f.is_restated as "restated",
    p.fiscal_year as "fiscalYear",p.period_type as "periodType",p.start_date as "periodStart",p.end_date as "periodEnd",
    f.published_at as "publishedAt",f.created_at as "storedAt",d.code as "sourceCode",f.data_version_id as "versionId"
    """;

  private static final String LATEST = """
    left join lateral (
      select m.as_of_date,count(*) as candidates,
      case when count(*)=1 then min(m.value) end as value,
      case when count(*)=1 then min(m.value_text) end as value_text,
      case when count(*)=1 then min(m.quality_status) end as quality,
      case when count(*)=1 then min(m.calculation_version) end as calculation_version,
      case when count(*)=1 then min(ds.code) end as source
      from financial_metrics m left join data_sources ds on ds.id=m.data_source_id
      where m.company_id=s.company_id and (m.security_id is null or m.security_id=s.id)
      and m.metric_definition_id=d.id and m.is_canonical and m.quality_status in ('VALID','WARNING')
      and m.is_derived=:derived and m.as_of_date<=:asOf
      and ((m.data_version_id is null and m.is_derived) or exists(select 1 from data_versions v where v.id=m.data_version_id and v.status='ACTIVATED'))
      group by m.as_of_date order by m.as_of_date desc limit 1
    ) metric on true
    """;

  private static final String METRIC_FIELDS = """
    d.code as "metricCode",d.name as "metricName",d.unit,metric.value,metric.value_text as "valueText",:derived as derived,metric.calculation_version as "calculationVersion",
    metric.as_of_date as "asOfDate",metric.source as "sourceCode",metric.quality as "qualityStatus",
    coalesce(metric.candidates,0) as "candidateCount",
    case when metric.candidates>1 then 'AMBIGUOUS' when metric.candidates is null then 'MISSING' else 'AVAILABLE' end as "metricState"
    """;

  private final UserReadJdbcRepository read;

  public UserFinancialQueryRepository(UserReadJdbcRepository read) {
    this.read = read;
  }

  public UserReadSupport.Page<Map<String, Object>> queryStatements(
      UUID companyId, String statementType, String cycle, LocalDate from, LocalDate to,
      int page, int size, String note) {
    var args = new HashMap<String, Object>();
    args.put("company", companyId);
    args.put("from", from);
    args.put("to", to);
    String where = STATEMENTS + " and p.end_date>=:from and p.end_date<=:to";
    if (statementType != null) {
      where += " and f.statement_type=:type";
      args.put("type", statementType);
    }
    if (cycle != null) {
      where += " and p.period_type=:period";
      args.put("period", cycle);
    }
    return read.page(
        "select " + FIELDS,
        where,
        "order by p.end_date desc,f.statement_type,f.report_scope,f.id",
        args,
        page,
        size,
        100,
        note,
        "Period filters use period end, not publication date. Financial reports belong to the company, shared by its listings.");
  }

  public Map<String, Object> findPublishedReport(UUID companyId, UUID id) {
    var args = new HashMap<String, Object>();
    args.put("company", companyId);
    args.put("id", id);
    return read.one("select " + FIELDS + STATEMENTS + " and f.id=:id", args, "Published statement");
  }

  public List<Map<String, Object>> findStatementItems(UUID statementId) {
    return read.rows(
        "select item_code as \"code\",item_name as \"name\",parent_item_code as \"parentCode\",value,unit,display_order as \"displayOrder\",is_total as \"total\" "
            + "from financial_statement_items where financial_statement_id=:id order by display_order nulls last,item_code,id limit 2001",
        Map.of("id", statementId));
  }

  public UserReadSupport.Page<Map<String, Object>> queryDefinitions(int page, int size) {
    return read.page(
        "select code,name,category,description,formula,unit,higher_is_better as \"higherIsBetter\"",
        "from metric_definitions",
        "order by category,code,id",
        Map.of(),
        page,
        size,
        100,
        "Definitions do not guarantee that every metric has data for a security or period.");
  }

  public UserReadSupport.Page<Map<String, Object>> queryMetrics(
      UUID companyId, List<String> codes, Boolean derived, String cycle, LocalDate from, LocalDate to,
      int page, int size) {
    var args = new HashMap<String, Object>();
    args.put("company", companyId);
    args.put("from", from);
    args.put("to", to);
    String where = """
      from financial_metrics m join metric_definitions d on d.id=m.metric_definition_id
      left join financial_periods p on p.id=m.financial_period_id left join data_sources s on s.id=m.data_source_id
      where m.company_id=:company and m.is_canonical and m.quality_status in ('VALID','WARNING')
      and ((m.data_version_id is null and m.is_derived) or exists(select 1 from data_versions v where v.id=m.data_version_id and v.status='ACTIVATED'))
      and m.as_of_date>=:from and m.as_of_date<=:to
      """;
    if (codes != null && !codes.isEmpty()) {
      args.put("codes", codes);
      where += " and d.code in (:codes)";
    }
    if (derived != null) {
      where += " and m.is_derived=:derived";
      args.put("derived", derived);
    }
    if (cycle != null) {
      where += " and p.period_type=:period";
      args.put("period", cycle);
    }
    return read.page(
        """
        select m.id,d.code,d.name,d.category,d.unit,m.as_of_date as "asOfDate",m.value,m.value_text as "valueText",
        m.is_derived as "derived",m.calculation_version as "calculationVersion",m.quality_status as "qualityStatus",s.code as "sourceCode",
        p.period_type as "periodType",p.end_date as "periodEnd",m.data_version_id as "versionId",m.created_at as "storedAt"
        """,
        where,
        "order by m.as_of_date desc,d.code,m.id",
        args,
        page,
        size,
        100,
        "Only canonical VALID/WARNING stored metrics. Provider observations and Java-derived calculations remain distinguishable.",
        "asOfDate is the stored metric date, not a verified public availability timestamp. No computation or model execution occurs on reads.");
  }

  public UserReadSupport.Page<Map<String, Object>> queryItems(
      UUID companyId, String statementType, int page, int size) {
    String from = "from (select distinct i.item_code as code,i.item_name as name,i.unit from financial_statement_items i join financial_statements f on f.id=i.financial_statement_id join data_versions v on v.id=f.data_version_id and v.status='ACTIVATED' where f.company_id=:company and f.is_current and f.is_canonical and f.statement_type=:type) t";
    return read.page(
        "select t.code,t.name,t.unit",
        from,
        "order by t.code,t.name,t.unit",
        Map.of("company", companyId, "type", statementType),
        page,
        size,
        100,
        "Item codes come from published reports for this company/type. Names and units can differ across periods.");
  }

  public List<Map<String, Object>> findMatrixPeriods(
      UUID companyId, String type, String cycle, String scope, LocalDate from, LocalDate to, int limit) {
    var args = new HashMap<String, Object>();
    args.put("company", companyId);
    args.put("type", type);
    args.put("from", from);
    args.put("to", to);
    args.put("limit", limit + 1);
    String filter = " and f.statement_type=:type and p.end_date between :from and :to";
    if ("QUARTER".equals(cycle)) {
      filter += " and p.period_type in ('Q1','Q2','Q3','Q4')";
    } else {
      filter += " and p.period_type=:period";
      args.put("period", cycle);
    }
    if (scope != null) {
      filter += " and f.report_scope=:scope";
      args.put("scope", scope);
    }
    return read.rows(
        "select distinct p.id,p.fiscal_year as \"fiscalYear\",p.period_type as \"periodType\",p.start_date as \"periodStart\",p.end_date as \"periodEnd\" "
            + STATEMENTS + filter + " order by p.end_date desc,p.id desc limit :limit",
        args);
  }

  public List<Map<String, Object>> findMatrixSeriesRows(
      UUID companyId, String type, String cycle, String scope, LocalDate from, LocalDate to,
      List<UUID> periodIds, List<String> codes) {
    var args = new HashMap<String, Object>();
    args.put("company", companyId);
    args.put("type", type);
    args.put("from", from);
    args.put("to", to);
    args.put("ids", periodIds);
    args.put("codes", codes);
    String filter = " and f.statement_type=:type and p.end_date between :from and :to";
    if ("QUARTER".equals(cycle)) {
      filter += " and p.period_type in ('Q1','Q2','Q3','Q4')";
    } else {
      filter += " and p.period_type=:period";
      args.put("period", cycle);
    }
    if (scope != null) {
      filter += " and f.report_scope=:scope";
      args.put("scope", scope);
    }
    return read.rows(
        """
        select p.id as "periodId",i.item_code as code,i.item_name as name,i.unit,i.value,
        f.id as "statementId",f.report_scope as scope,f.period_basis as basis,f.currency,f.unit_scale as "unitScale",d.code as "sourceCode",f.published_at as "publishedAt"
        """ + STATEMENTS.replace("where f.company_id", "join financial_statement_items i on i.financial_statement_id=f.id where f.company_id")
            + filter + " and p.id in (:ids) and i.item_code in (:codes) order by p.end_date,p.id,i.item_code,f.report_scope,f.id",
        args);
  }

  public List<Map<String, Object>> findReportingPeriods(UUID companyId) {
    return read.rows(
        "select p.id,p.fiscal_year as \"fiscalYear\",p.period_type as \"periodType\",p.start_date as \"periodStart\",p.end_date as \"periodEnd\",count(*) as \"reportCount\" "
            + STATEMENTS
            + " group by p.id,p.fiscal_year,p.period_type,p.start_date,p.end_date order by p.end_date desc,p.period_type,p.id limit 501",
        Map.of("company", companyId));
  }

  public Map<String, Object> findStatementLineage(UUID reportId) {
    return read.one(
        """
        select f.id as "statementId",f.data_version_id as "versionId",v.status as "versionStatus",v.activated_at as "activatedAt",
        d.code as "sourceCode",d.name as "sourceName",r.fetched_at as "sourceFetchedAt",
        case when r.source_url ~* '^https?://' then r.source_url end as "sourceUrl",
        f.published_at as "publishedAt",f.report_scope as "reportScope",f.period_basis as "periodBasis",f.created_at as "storedAt"
        from financial_statements f join data_sources d on d.id=f.data_source_id join data_versions v on v.id=f.data_version_id
        left join raw_payloads r on r.id=f.raw_payload_id where f.id=:id
        """,
        Map.of("id", reportId),
        "Statement source");
  }

  public Map<String, Object> findMetricLineage(UUID metricId, UUID companyId) {
    return read.one(
        """
        select m.id,m.company_id as "companyId",m.security_id as "observedSecurityId",
        d.code,d.name,d.category,d.description,d.formula as "catalogFormula",d.unit,d.higher_is_better as "higherIsBetter",
        m.value,m.value_text as "valueText",m.as_of_date as "asOfDate",m.is_derived as "derived",
        m.calculation_version as "calculationVersion",m.quality_status as "qualityStatus",m.created_at as "storedAt",
        p.fiscal_year as "fiscalYear",p.period_type as "periodType",p.start_date as "periodStart",p.end_date as "periodEnd",
        s.code as "sourceCode",s.name as "sourceName",m.data_version_id as "versionId",v.status as "versionStatus",
        v.activated_at as "activatedAt",r.fetched_at as "sourceFetchedAt",r.source_url as "sourceUrl"
        from financial_metrics m join metric_definitions d on d.id=m.metric_definition_id
        left join financial_periods p on p.id=m.financial_period_id left join data_sources s on s.id=m.data_source_id
        left join data_versions v on v.id=m.data_version_id left join raw_payloads r on r.id=m.raw_payload_id
        where m.id=:id and m.company_id=:company and m.is_canonical and m.quality_status in ('VALID','WARNING')
        and ((m.data_version_id is null and m.is_derived) or v.status='ACTIVATED')
        """,
        Map.of("id", metricId, "company", companyId),
        "Published metric");
  }

  public void verifyMetricCode(String code) {
    read.one("select code from metric_definitions where code=:metric", Map.of("metric", code), "Metric");
  }

  public UserReadSupport.Page<Map<String, Object>> screenMetrics(
      String search, String exchange, String industry, String metric, boolean derived, LocalDate asOf,
      BigDecimal min, BigDecimal max, String ordering, int page, int size, String note) {
    var params = new HashMap<String, Object>();
    params.put("search", search);
    params.put("metric", metric);
    params.put("derived", derived);
    params.put("asOf", asOf);
    String where = UserReadJdbcRepository.SECURITY_JOIN + "left join metric_definitions d on d.code=:metric " + LATEST
        + "where s.is_active and c.is_active and (s.symbol ilike :search or c.legal_name ilike :search or c.short_name ilike :search)";
    if (exchange != null && !exchange.isBlank()) {
      where += " and s.exchange=:exchange";
      params.put("exchange", exchange);
    }
    if (industry != null) {
      where += " and c.industry_name=:industry";
      params.put("industry", industry);
    }
    if (min != null) {
      where += " and metric.value>=:min";
      params.put("min", min);
    }
    if (max != null) {
      where += " and metric.value<=:max";
      params.put("max", max);
    }
    return read.page(
        "select " + UserReadJdbcRepository.SECURITY_FIELDS + "," + METRIC_FIELDS,
        where,
        "order by " + ordering + "s.symbol,s.exchange,s.id",
        params,
        page,
        size,
        100,
        note,
        "Only active tracked listings are screened. Missing or ambiguous values do not pass numeric bounds.");
  }

  public List<Map<String, Object>> findMetricDefinitionsByCodes(List<String> codes) {
    return read.rows("select code,name,category,unit from metric_definitions where code in (:codes) order by code", Map.of("codes", codes));
  }

  public List<Map<String, Object>> findSecurityMetricRows(List<UUID> securityIds, List<String> codes, boolean derived, LocalDate asOf) {
    var params = new HashMap<String, Object>();
    params.put("ids", securityIds);
    params.put("codes", codes);
    params.put("derived", derived);
    params.put("asOf", asOf);
    return read.rows(
        "select s.id as \"securityId\"," + METRIC_FIELDS + UserReadJdbcRepository.SECURITY_JOIN
            + "join metric_definitions d on d.code in (:codes) " + LATEST
            + "where s.id in (:ids) order by s.symbol,s.exchange,d.code",
        params);
  }
}
