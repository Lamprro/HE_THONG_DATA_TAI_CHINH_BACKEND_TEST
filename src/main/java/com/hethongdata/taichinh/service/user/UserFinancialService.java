package com.hethongdata.taichinh.service.user;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;

@Service @Transactional(readOnly=true,timeout=15,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class UserFinancialService {
  private final UserReadSupport read;private final UserSecurityService securities;
  public UserFinancialService(UserReadSupport read,UserSecurityService securities) {this.read=read;this.securities=securities;}
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
  private Map<String,Object> company(String symbol,String exchange) {return new HashMap<>(Map.of("company",UUID.fromString(securities.resolve(symbol,exchange).get("companyId").toString())));}
  private String period(String type) {return option(type,Set.of("FY","Q1","Q2","Q3","Q4"));}
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
}
