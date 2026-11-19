package com.hethongdata.taichinh.service.user;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;

/** Financial statements, metrics, period matrix views, metric comparison/screening, periods and statement/metric lineage. */
@Service
@Transactional(readOnly=true,timeout=15,isolation=Isolation.REPEATABLE_READ)
public class UserFinancialService {
  private final UserReadSupport read;
  public UserFinancialService(UserReadSupport read) {this.read=read;}
  static final String STATEMENTS="""
    from financial_statements f join financial_periods p on p.id=f.financial_period_id
    join data_sources d on d.id=f.data_source_id join data_versions v on v.id=f.data_version_id and v.status='ACTIVATED'
    where f.company_id=:company and f.is_current and f.is_canonical
    """;
  static final String FIELDS="""
    f.id,f.company_id as "companyId",f.statement_type as "statementType",f.report_scope as "reportScope",f.period_basis as "periodBasis",
    f.currency,f.unit_scale as "unitScale",f.audited,f.revision_no as "revision",f.is_restated as "restated",
    p.fiscal_year as "fiscalYear",p.period_type as "periodType",p.start_date as "periodStart",p.end_date as "periodEnd",
    f.published_at as "publishedAt",f.created_at as "storedAt",d.code as "sourceCode",f.data_version_id as "versionId"
    """;
  static final String NOTE="Current canonical reports only. Unknown publication dates, report scope and cumulative/standalone basis stay unknown; no point-in-time or TTM certification.";
  private Map<String,Object> company(String symbol,String exchange) {return new HashMap<>(Map.of("company",UUID.fromString(read.resolve(symbol,exchange).get("companyId").toString())));}
  private String period(String type) {return option(type,Set.of("FY","Q1","Q2","Q3","Q4"));}

  // ---- statements and metrics ----
  public Page<Map<String,Object>> statements(String symbol,String exchange,String type,String period,LocalDate from,LocalDate to,int page,int size) {
    var args=company(symbol,exchange);var range=window(from,to,10,30);args.put("from",range.from());args.put("to",range.to());
    String where=STATEMENTS+" and p.end_date>=:from and p.end_date<=:to";
    String statement=option(type,Set.of("BALANCE_SHEET","INCOME_STATEMENT","CASH_FLOW")),cycle=period(period);
    if(statement!=null) {where+=" and f.statement_type=:type";args.put("type",statement);}if(cycle!=null) {where+=" and p.period_type=:period";args.put("period",cycle);}
    return read.page("select "+FIELDS,where,"order by p.end_date desc,f.statement_type,f.report_scope,f.id",args,page,size,100,NOTE,"Period filters use period end, not publication date. Financial reports belong to the company, shared by its listings.");
  }
  public Data<Map<String,Object>> statement(String symbol,String exchange,UUID id) {
    var report=new LinkedHashMap<>(publishedReport(symbol,exchange,id));
    var items=read.rows("select item_code as \"code\",item_name as \"name\",parent_item_code as \"parentCode\",value,unit,display_order as \"displayOrder\",is_total as \"total\" from financial_statement_items where financial_statement_id=:id order by display_order nulls last,item_code,id limit 2001",Map.of("id",id));
    if(items.size()>2000) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"Statement exceeds 2000 display items");
    report.put("items",items);return data(report,NOTE,"Read each item's stored unit. Do not multiply monetary values by report unitScale without checking the source normalization contract.");
  }
  public Map<String,Object> publishedReport(String symbol,String exchange,UUID id) {
    var args=company(symbol,exchange);args.put("id",id);return read.one("select "+FIELDS+STATEMENTS+" and f.id=:id",args,"Published statement");
  }
  public Page<Map<String,Object>> definitions(int page,int size) {
    return read.page("select code,name,category,description,formula,unit,higher_is_better as \"higherIsBetter\"","from metric_definitions","order by category,code,id",Map.of(),page,size,100,"Definitions do not guarantee that every metric has data for a security or period.");
  }
  public Page<Map<String,Object>> metrics(String symbol,String exchange,String codes,String origin,String period,LocalDate from,LocalDate to,int page,int size) {
    var args=company(symbol,exchange);var range=window(from,to,10,30);args.put("from",range.from());args.put("to",range.to());
    String where="""
      from financial_metrics m join metric_definitions d on d.id=m.metric_definition_id
      left join financial_periods p on p.id=m.financial_period_id left join data_sources s on s.id=m.data_source_id
      where m.company_id=:company and m.is_canonical and m.quality_status in ('VALID','WARNING')
      and ((m.data_version_id is null and m.is_derived) or exists(select 1 from data_versions v where v.id=m.data_version_id and v.status='ACTIVATED'))
      and m.as_of_date>=:from and m.as_of_date<=:to
      """;
    if(codes!=null&&!codes.isBlank()) {var values=Arrays.stream(codes.split(",",-1)).map(UserReadSupport::code).distinct().toList();if(values.size()>20)throw new IllegalArgumentException("At most 20 metric codes");args.put("codes",values);where+=" and d.code in (:codes)";}
    String selected=option(origin,Set.of("PROVIDER","DERIVED")),cycle=period(period);
    if(selected!=null) {where+=" and m.is_derived=:derived";args.put("derived","DERIVED".equals(selected));}if(cycle!=null) {where+=" and p.period_type=:period";args.put("period",cycle);}
    return read.page("""
      select m.id,d.code,d.name,d.category,d.unit,m.as_of_date as "asOfDate",m.value,m.value_text as "valueText",
      m.is_derived as "derived",m.calculation_version as "calculationVersion",m.quality_status as "qualityStatus",s.code as "sourceCode",
      p.period_type as "periodType",p.end_date as "periodEnd",m.data_version_id as "versionId",m.created_at as "storedAt"
      """,where,"order by m.as_of_date desc,d.code,m.id",args,page,size,100,"Only canonical VALID/WARNING stored metrics. Provider observations and Java-derived calculations remain distinguishable.","asOfDate is the stored metric date, not a verified public availability timestamp. No computation or model execution occurs on reads.");
  }

  // ---- statement item catalog and period matrix ----
  private String type(String value) {String result=option(value,Set.of("BALANCE_SHEET","INCOME_STATEMENT","CASH_FLOW"));if(result==null)throw new IllegalArgumentException("statement type required");return result;}
  public Page<Map<String,Object>> items(String symbol,String exchange,String type,int page,int size) {
    var company=UUID.fromString(read.resolve(symbol,exchange).get("companyId").toString());
    String from="from (select distinct i.item_code as code,i.item_name as name,i.unit from financial_statement_items i join financial_statements f on f.id=i.financial_statement_id join data_versions v on v.id=f.data_version_id and v.status='ACTIVATED' where f.company_id=:company and f.is_current and f.is_canonical and f.statement_type=:type) t";
    return read.page("select t.code,t.name,t.unit",from,"order by t.code,t.name,t.unit",Map.of("company",company,"type",type(type)),page,size,100,"Item codes come from published reports for this company/type. Names and units can differ across periods.");
  }
  public Data<Map<String,Object>> matrix(String symbol,String exchange,String type,String period,String scope,String codes,LocalDate from,LocalDate to,int limit) {
    if(limit<1||limit>40) throw new IllegalArgumentException("periodLimit must be 1..40");var selected=codes(codes,20);
    var security=read.resolve(symbol,exchange);var range=window(from,to,10,30);var args=new HashMap<String,Object>();
    args.put("company",UUID.fromString(security.get("companyId").toString()));args.put("type",type(type));args.put("from",range.from());args.put("to",range.to());args.put("limit",limit+1);
    String cycle=option(period==null?"QUARTER":period,Set.of("QUARTER","FY","Q1","Q2","Q3","Q4"));
    if(cycle==null) throw new IllegalArgumentException("period required");String filter=" and f.statement_type=:type and p.end_date between :from and :to";
    if("QUARTER".equals(cycle)) filter+=" and p.period_type in ('Q1','Q2','Q3','Q4')";
    else {filter+=" and p.period_type=:period";args.put("period",cycle);}
    String requestedScope=option(scope,Set.of("CONSOLIDATED","SEPARATE","UNKNOWN"));if(requestedScope!=null) {filter+=" and f.report_scope=:scope";args.put("scope",requestedScope);}
    var periods=new ArrayList<>(read.rows("select distinct p.id,p.fiscal_year as \"fiscalYear\",p.period_type as \"periodType\",p.start_date as \"periodStart\",p.end_date as \"periodEnd\" "+STATEMENTS+filter+" order by p.end_date desc,p.id desc limit :limit",args));
    boolean earlier=periods.size()>limit;if(earlier)periods.removeLast();Collections.reverse(periods);
    var series=new LinkedHashMap<List<Object>,Map<String,Object>>();var found=new HashSet<String>();
    if(!periods.isEmpty()) {
      args.put("ids",periods.stream().map(p->UUID.fromString(p.get("id").toString())).toList());args.put("codes",selected);
      var rows=read.rows("""
        select p.id as "periodId",i.item_code as code,i.item_name as name,i.unit,i.value,
        f.id as "statementId",f.report_scope as scope,f.period_basis as basis,f.currency,f.unit_scale as "unitScale",d.code as "sourceCode",f.published_at as "publishedAt"
        """+STATEMENTS.replace("where f.company_id", "join financial_statement_items i on i.financial_statement_id=f.id where f.company_id")+
        filter+" and p.id in (:ids) and i.item_code in (:codes) order by p.end_date,p.id,i.item_code,f.report_scope,f.id",args);
      for(var row:rows) {
        List<Object> key=Arrays.asList(row.get("code"),row.get("scope"),row.get("basis"),row.get("currency"),row.get("unit"),row.get("unitScale"));
        var line=series.computeIfAbsent(key,k->{var item=new LinkedHashMap<String,Object>();for(String field:List.of("code","name","unit","scope","basis","currency","unitScale"))item.put(field,row.get(field));item.put("values",new LinkedHashMap<String,Object>());return item;});
        @SuppressWarnings("unchecked") var values=(Map<String,Object>)line.get("values");var point=new LinkedHashMap<String,Object>();
        for(String field:List.of("value","statementId","sourceCode","publishedAt"))point.put(field,row.get(field));
        if(values.putIfAbsent(row.get("periodId").toString(),point)!=null)throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Multiple canonical cells for the same period/scope/unit");
        found.add(row.get("code").toString());
      }
    }
    var result=new LinkedHashMap<String,Object>();result.put("security",security);result.put("statementType",args.get("type"));result.put("periods",periods);result.put("series",List.copyOf(series.values()));
    result.put("hasEarlierPeriods",earlier);result.put("missingCodes",selected.stream().filter(c->!found.contains(c)).toList());
    return data(result,NOTE,"Cells are stored values; different report scope, period basis, currency and unit remain separate series. Missing cells stay absent, never zero.","This is a current-canonical period view, not a historical-vintage backtest. Quarterly and annual series are never combined by default.");
  }

  // ---- periods and lineage ----
  public Data<List<Map<String,Object>>> periods(String symbol,String exchange) {
    var company=UUID.fromString(read.resolve(symbol,exchange).get("companyId").toString());
    var rows=read.rows("select p.id,p.fiscal_year as \"fiscalYear\",p.period_type as \"periodType\",p.start_date as \"periodStart\",p.end_date as \"periodEnd\",count(*) as \"reportCount\" "+STATEMENTS+
        " group by p.id,p.fiscal_year,p.period_type,p.start_date,p.end_date order by p.end_date desc,p.period_type,p.id limit 501",Map.of("company",company));
    if(rows.size()>500) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"More than 500 reporting periods; use paginated statements");
    return data(rows,"Only periods with current canonical activated reports. A period end is not a filing date.");
  }
  public Data<Map<String,Object>> lineage(String symbol,String exchange,UUID report) {
    publishedReport(symbol,exchange,report); // Same visibility/ownership gate, without loading report items.
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
  /** Sources for the exact metric observation selected in the UI, never a latest-row substitute. */
  public Data<Map<String,Object>> metricLineage(String symbol,String exchange,UUID id) {
    var security=read.resolve(symbol,exchange);
    var row=read.one("""
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
      """,Map.of("id",id,"company",UUID.fromString(security.get("companyId").toString())),"Published metric");
    row.put("sourceUrl",publicSourceUrl(row.get("sourceUrl")));
    row.put("origin",Boolean.TRUE.equals(row.get("derived"))?"DERIVED":"PROVIDER");
    row.put("pointInTimeCertified",false);
    return data(row,"Same publication and company ownership rules as the metric list. observedSecurityId identifies the stored listing when present.",
        "catalogFormula is the current definition, not proof of a provider formula or the historical calculation. calculationVersion identifies the stored Java calculation; raw input snapshots remain private.",
        "asOfDate is not a verified release date. Source links may be null; missing period, publication and version evidence is not inferred.");
  }

  // ---- comparison and screener ----
  // Aggregate ties instead of silently choosing one provider or scope by UUID.
  private static final String LATEST="""
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
  private static final String METRIC_FIELDS="""
    d.code as "metricCode",d.name as "metricName",d.unit,metric.value,metric.value_text as "valueText",:derived as derived,metric.calculation_version as "calculationVersion",
    metric.as_of_date as "asOfDate",metric.source as "sourceCode",metric.quality as "qualityStatus",
    coalesce(metric.candidates,0) as "candidateCount",
    case when metric.candidates>1 then 'AMBIGUOUS' when metric.candidates is null then 'MISSING' else 'AVAILABLE' end as "metricState"
    """;
  static final String COMPARISON_NOTE="Latest stored metric on or before asOf, not a verified publication-time snapshot. Origin is selected explicitly; multiple canonical candidates at the latest date return AMBIGUOUS and no value. Units and dates can differ; no composite score or zero imputation.";
  private Map<String,Object> comparisonArgs(String origin,LocalDate asOf) {
    String selected=option(origin==null?"PROVIDER":origin,Set.of("PROVIDER","DERIVED"));if(selected==null)throw new IllegalArgumentException("origin required");
    var range=window(asOf,asOf,0,0);var args=new HashMap<String,Object>();args.put("derived","DERIVED".equals(selected));args.put("asOf",range.to());return args;
  }
  private BigDecimal bound(String value) {
    if(value==null)return null;if(value.length()>60)throw new IllegalArgumentException("Metric bound is too long");
    try {var n=new BigDecimal(value);if(n.precision()>38||n.scale()>12||n.scale()< -38)throw new NumberFormatException();return n;}
    catch(NumberFormatException e) {throw new IllegalArgumentException("Metric bounds must be finite decimals with precision <= 38 and scale <= 12");}
  }
  public Page<Map<String,Object>> screen(String search,String exchange,String industry,String metric,String origin,LocalDate asOf,String min,String max,String sort,int page,int size) {
    paging(page,size,100);var params=comparisonArgs(origin,asOf);params.put("search",search(search));params.put("metric",metric==null?null:code(metric));
    var lower=bound(min);var upper=bound(max);if(lower!=null&&upper!=null&&lower.compareTo(upper)>0)throw new IllegalArgumentException("min must be <= max");
    String order=option(sort==null?"SYMBOL":sort,Set.of("SYMBOL","METRIC_ASC","METRIC_DESC"));if(order==null)throw new IllegalArgumentException("sort required");
    if(metric==null&&(lower!=null||upper!=null||!order.equals("SYMBOL")))throw new IllegalArgumentException("metric is required for bounds or metric sorting");
    if(metric!=null)read.one("select code from metric_definitions where code=:metric",params,"Metric");
    String where=SECURITY_JOIN+"left join metric_definitions d on d.code=:metric "+LATEST+
      "where s.is_active and c.is_active and (s.symbol ilike :search or c.legal_name ilike :search or c.short_name ilike :search)";
    if(exchange!=null&&!exchange.isBlank()) {where+=" and s.exchange=:exchange";params.put("exchange",code(exchange));}
    if(industry!=null) {if(industry.isBlank()||industry.length()>200)throw new IllegalArgumentException("industry must be 1..200 characters");where+=" and c.industry_name=:industry";params.put("industry",industry);}
    if(lower!=null){where+=" and metric.value>=:min";params.put("min",lower);}if(upper!=null){where+=" and metric.value<=:max";params.put("max",upper);}
    String ordering=switch(order){case "METRIC_ASC"->"metric.value asc nulls last,";case "METRIC_DESC"->"metric.value desc nulls last,";default->"";};
    return read.page("select "+SECURITY_FIELDS+","+METRIC_FIELDS,where,"order by "+ordering+"s.symbol,s.exchange,s.id",params,page,size,100,COMPARISON_NOTE,"Only active tracked listings are screened. Missing or ambiguous values do not pass numeric bounds.");
  }
  public Data<Map<String,Object>> compare(String symbols,String exchange,String codes,String origin,LocalDate asOf) {
    var selected=codes(codes,20);var requested=codes(symbols,10);var params=comparisonArgs(origin,asOf);params.put("codes",selected);
    var profiles=requested.stream().map(symbol->read.resolve(symbol,exchange)).toList();params.put("ids",profiles.stream().map(s->UUID.fromString(s.get("id").toString())).toList());
    var definitions=read.rows("select code,name,category,unit from metric_definitions where code in (:codes) order by code",params);
    var known=definitions.stream().map(d->d.get("code").toString()).toList();
    var rows=read.rows("select s.id as \"securityId\","+METRIC_FIELDS+SECURITY_JOIN+"join metric_definitions d on d.code in (:codes) "+LATEST+"where s.id in (:ids) order by s.symbol,s.exchange,d.code",params);
    var items=new ArrayList<Map<String,Object>>();for(var profile:profiles){var item=new LinkedHashMap<>(profile);item.put("metrics",rows.stream().filter(r->r.get("securityId").equals(profile.get("id"))).toList());items.add(item);}
    var result=new LinkedHashMap<String,Object>();result.put("asOf",params.get("asOf"));result.put("origin",Boolean.TRUE.equals(params.get("derived"))?"DERIVED":"PROVIDER");
    result.put("definitions",definitions);result.put("unknownCodes",selected.stream().filter(c->!known.contains(c)).toList());result.put("securities",items);return data(result,COMPARISON_NOTE);
  }
}
