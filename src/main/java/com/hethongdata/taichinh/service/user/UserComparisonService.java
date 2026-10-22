package com.hethongdata.taichinh.service.user;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;

@Service
@Transactional(readOnly=true,timeout=15,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class UserComparisonService {
  private final UserReadSupport read;private final UserSecurityService securities;
  public UserComparisonService(UserReadSupport read,UserSecurityService securities) {this.read=read;this.securities=securities;}
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
  static final String NOTE="Latest stored metric on or before asOf, not a verified publication-time snapshot. Origin is selected explicitly; multiple canonical candidates at the latest date return AMBIGUOUS and no value. Units and dates can differ; no composite score or zero imputation.";
  private Map<String,Object> args(String origin,LocalDate asOf) {
    String selected=option(origin==null?"PROVIDER":origin,Set.of("PROVIDER","DERIVED"));if(selected==null)throw new IllegalArgumentException("origin required");
    var range=window(asOf,asOf,0,0);var args=new HashMap<String,Object>();args.put("derived","DERIVED".equals(selected));args.put("asOf",range.to());return args;
  }
  private BigDecimal bound(String value) {
    if(value==null)return null;if(value.length()>60)throw new IllegalArgumentException("Metric bound is too long");
    try {var n=new BigDecimal(value);if(n.precision()>38||n.scale()>12||n.scale()< -38)throw new NumberFormatException();return n;}
    catch(NumberFormatException e) {throw new IllegalArgumentException("Metric bounds must be finite decimals with precision <= 38 and scale <= 12");}
  }
  public Page<Map<String,Object>> screen(String search,String exchange,String industry,String metric,String origin,LocalDate asOf,String min,String max,String sort,int page,int size) {
    paging(page,size,100);var params=args(origin,asOf);params.put("search",search(search));params.put("metric",metric==null?null:code(metric));
    var lower=bound(min);var upper=bound(max);if(lower!=null&&upper!=null&&lower.compareTo(upper)>0)throw new IllegalArgumentException("min must be <= max");
    String order=option(sort==null?"SYMBOL":sort,Set.of("SYMBOL","METRIC_ASC","METRIC_DESC"));if(order==null)throw new IllegalArgumentException("sort required");
    if(metric==null&&(lower!=null||upper!=null||!order.equals("SYMBOL")))throw new IllegalArgumentException("metric is required for bounds or metric sorting");
    if(metric!=null)read.one("select code from metric_definitions where code=:metric",params,"Metric");
    String where=UserSecurityService.JOIN+"left join metric_definitions d on d.code=:metric "+LATEST+
      "where s.is_active and c.is_active and (s.symbol ilike :search or c.legal_name ilike :search or c.short_name ilike :search)";
    if(exchange!=null&&!exchange.isBlank()) {where+=" and s.exchange=:exchange";params.put("exchange",code(exchange));}
    if(industry!=null) {if(industry.isBlank()||industry.length()>200)throw new IllegalArgumentException("industry must be 1..200 characters");where+=" and c.industry_name=:industry";params.put("industry",industry);}
    if(lower!=null){where+=" and metric.value>=:min";params.put("min",lower);}if(upper!=null){where+=" and metric.value<=:max";params.put("max",upper);}
    String ordering=switch(order){case "METRIC_ASC"->"metric.value asc nulls last,";case "METRIC_DESC"->"metric.value desc nulls last,";default->"";};
    return read.page("select "+UserSecurityService.FIELDS+","+METRIC_FIELDS,where,"order by "+ordering+"s.symbol,s.exchange,s.id",params,page,size,100,NOTE,"Only active tracked listings are screened. Missing or ambiguous values do not pass numeric bounds.");
  }
  public Data<Map<String,Object>> compare(String symbols,String exchange,String codes,String origin,LocalDate asOf) {
    var selected=codes(codes,20);var requested=codes(symbols,10);var params=args(origin,asOf);params.put("codes",selected);
    var profiles=requested.stream().map(symbol->securities.resolve(symbol,exchange)).toList();params.put("ids",profiles.stream().map(s->UUID.fromString(s.get("id").toString())).toList());
    var definitions=read.rows("select code,name,category,unit from metric_definitions where code in (:codes) order by code",params);
    var known=definitions.stream().map(d->d.get("code").toString()).toList();
    var rows=read.rows("select s.id as \"securityId\","+METRIC_FIELDS+UserSecurityService.JOIN+"join metric_definitions d on d.code in (:codes) "+LATEST+"where s.id in (:ids) order by s.symbol,s.exchange,d.code",params);
    var items=new ArrayList<Map<String,Object>>();for(var profile:profiles){var item=new LinkedHashMap<>(profile);item.put("metrics",rows.stream().filter(r->r.get("securityId").equals(profile.get("id"))).toList());items.add(item);}
    var result=new LinkedHashMap<String,Object>();result.put("asOf",params.get("asOf"));result.put("origin",Boolean.TRUE.equals(params.get("derived"))?"DERIVED":"PROVIDER");
    result.put("definitions",definitions);result.put("unknownCodes",selected.stream().filter(c->!known.contains(c)).toList());result.put("securities",items);return data(result,NOTE);
  }
}
