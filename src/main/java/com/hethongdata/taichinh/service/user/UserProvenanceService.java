package com.hethongdata.taichinh.service.user;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;

/** Sources for the exact observation selected in the UI, never a latest-row substitute. */
@Service
@Transactional(readOnly=true,timeout=15,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
public class UserProvenanceService {
  private final UserReadSupport read;
  private final UserSecurityService securities;
  public UserProvenanceService(UserReadSupport read,UserSecurityService securities) {this.read=read;this.securities=securities;}

  public Data<Map<String,Object>> price(String symbol,String exchange,long id) {
    if(id<1) throw new IllegalArgumentException("Price ID must be positive");
    var security=securities.resolve(symbol,exchange);
    var row=read.one("select "+UserMarketService.PRICE_FIELDS+"""
      ,v.status as "versionStatus",v.activated_at as "activatedAt",r.fetched_at as "sourceFetchedAt",r.source_url as "sourceUrl"
      """+UserMarketService.PRICES+"""
      left join raw_payloads r on r.id=p.raw_payload_id
      where p.id=:id and p.security_id=:security and p.is_canonical and p.interval_code in ('1d','snapshot')
      """,Map.of("id",id,"security",UUID.fromString(security.get("id").toString())),"Published price");
    row.put("sourceUrl",publicSourceUrl(row.get("sourceUrl")));
    row.put("pointInTimeCertified",false);
    return data(row,UserMarketService.PRICE_NOTE,"Source belongs to this exact price ID. Daily and snapshot observations stay separate. Raw bodies and request parameters are omitted; unsafe source URLs are null.");
  }

  public Data<Map<String,Object>> metric(String symbol,String exchange,UUID id) {
    var security=securities.resolve(symbol,exchange);
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
}
