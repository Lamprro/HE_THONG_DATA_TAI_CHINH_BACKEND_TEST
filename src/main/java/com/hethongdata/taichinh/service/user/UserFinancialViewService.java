package com.hethongdata.taichinh.service.user;

import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;

@Service
@Transactional(readOnly=true,timeout=15,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class UserFinancialViewService {
  private final UserReadSupport read;private final UserSecurityService securities;
  public UserFinancialViewService(UserReadSupport read,UserSecurityService securities) {this.read=read;this.securities=securities;}
  private String type(String value) {String result=option(value,Set.of("BALANCE_SHEET","INCOME_STATEMENT","CASH_FLOW"));if(result==null)throw new IllegalArgumentException("statement type required");return result;}
  public Page<Map<String,Object>> items(String symbol,String exchange,String type,int page,int size) {
    var company=UUID.fromString(securities.resolve(symbol,exchange).get("companyId").toString());
    String from="from (select distinct i.item_code as code,i.item_name as name,i.unit from financial_statement_items i join financial_statements f on f.id=i.financial_statement_id join data_versions v on v.id=f.data_version_id and v.status='ACTIVATED' where f.company_id=:company and f.is_current and f.is_canonical and f.statement_type=:type) t";
    return read.page("select t.code,t.name,t.unit",from,"order by t.code,t.name,t.unit",Map.of("company",company,"type",type(type)),page,size,100,"Item codes come from published reports for this company/type. Names and units can differ across periods.");
  }
  public Data<Map<String,Object>> matrix(String symbol,String exchange,String type,String period,String scope,String codes,LocalDate from,LocalDate to,int limit) {
    if(limit<1||limit>40) throw new IllegalArgumentException("periodLimit must be 1..40");var selected=codes(codes,20);
    var security=securities.resolve(symbol,exchange);var range=window(from,to,10,30);var args=new HashMap<String,Object>();
    args.put("company",UUID.fromString(security.get("companyId").toString()));args.put("type",type(type));args.put("from",range.from());args.put("to",range.to());args.put("limit",limit+1);
    String cycle=option(period==null?"QUARTER":period,Set.of("QUARTER","FY","Q1","Q2","Q3","Q4"));
    if(cycle==null) throw new IllegalArgumentException("period required");String filter=" and f.statement_type=:type and p.end_date between :from and :to";
    if("QUARTER".equals(cycle)) filter+=" and p.period_type in ('Q1','Q2','Q3','Q4')";
    else {filter+=" and p.period_type=:period";args.put("period",cycle);}
    String requestedScope=option(scope,Set.of("CONSOLIDATED","SEPARATE","UNKNOWN"));if(requestedScope!=null) {filter+=" and f.report_scope=:scope";args.put("scope",requestedScope);}
    var periods=new ArrayList<>(read.rows("select distinct p.id,p.fiscal_year as \"fiscalYear\",p.period_type as \"periodType\",p.start_date as \"periodStart\",p.end_date as \"periodEnd\" "+UserFinancialService.STATEMENTS+filter+" order by p.end_date desc,p.id desc limit :limit",args));
    boolean earlier=periods.size()>limit;if(earlier)periods.removeLast();Collections.reverse(periods);
    var series=new LinkedHashMap<List<Object>,Map<String,Object>>();var found=new HashSet<String>();
    if(!periods.isEmpty()) {
      args.put("ids",periods.stream().map(p->UUID.fromString(p.get("id").toString())).toList());args.put("codes",selected);
      var rows=read.rows("""
        select p.id as "periodId",i.item_code as code,i.item_name as name,i.unit,i.value,
        f.id as "statementId",f.report_scope as scope,f.period_basis as basis,f.currency,f.unit_scale as "unitScale",d.code as "sourceCode",f.published_at as "publishedAt"
        """+UserFinancialService.STATEMENTS.replace("where f.company_id", "join financial_statement_items i on i.financial_statement_id=f.id where f.company_id")+
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
    return data(result,UserFinancialService.NOTE,"Cells are stored values; different report scope, period basis, currency and unit remain separate series. Missing cells stay absent, never zero.","This is a current-canonical period view, not a historical-vintage backtest. Quarterly and annual series are never combined by default.");
  }
}
