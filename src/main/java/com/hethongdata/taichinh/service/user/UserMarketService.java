package com.hethongdata.taichinh.service.user;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.hethongdata.taichinh.service.user.UserReadSupport.*;

/** Prices, quotes, market overview/filters, indices, macro series, supplementary observations and price lineage. */
@Service
@Transactional(readOnly=true,timeout=15,isolation=Isolation.REPEATABLE_READ)
public class UserMarketService {
  private final UserReadSupport read;
  public UserMarketService(UserReadSupport read) {this.read=read;}
  static final String PRICE_NOTE="Stored canonical prices, not realtime. Gaps are not filled; nominal close changes are not total returns or adjusted returns.";
  static final String PRICE_FIELDS="""
    p.id,p.price_timestamp as "timestamp",(p.price_timestamp at time zone 'Asia/Ho_Chi_Minh')::date as "tradingDate",
    p.interval_code as "interval",p.open_price as "open",p.high_price as "high",p.low_price as "low",p.close_price as "close",
    p.volume,p.trading_value as "tradingValue",p.price_basis as "priceBasis",p.updated_at as "storedAt",
    d.code as "sourceCode",d.name as "sourceName",p.data_version_id as "versionId",security.currency,'SHARES' as "volumeUnit"
    """;
  static final String PRICES=" from market_prices p join securities security on security.id=p.security_id join data_sources d on d.id=p.data_source_id join data_versions v on v.id=p.data_version_id and v.status='ACTIVATED' ";

  // ---- prices and market overview ----
  public Page<Map<String,Object>> prices(String symbol,String exchange,String interval,LocalDate from,LocalDate to,int page,int size) {
    var security=read.resolve(symbol,exchange);String frame=option(interval==null?"1D":interval,Set.of("1D","SNAPSHOT")).toLowerCase(Locale.ROOT);
    var window=window(from,to,1,15);var args=new HashMap<String,Object>();args.put("id",UUID.fromString(security.get("id").toString()));args.put("interval",frame);args.put("start",window.start());args.put("until",window.until());
    return read.page("select "+PRICE_FIELDS,PRICES+"where p.security_id=:id and p.is_canonical and p.interval_code=:interval and p.price_timestamp>=:start and p.price_timestamp<:until",
        "order by p.price_timestamp,p.id",args,page,size,500,PRICE_NOTE,"Daily and snapshot series are separate. Amounts use the security currency; volume is shares.");
  }
  public Data<Map<String,Object>> latest(String symbol,String exchange) {
    var security=read.resolve(symbol,exchange);var value=new LinkedHashMap<String,Object>();value.put("security",security);
    value.put("daily",read.rows("select "+PRICE_FIELDS+PRICES+"where p.security_id=:id and p.is_canonical and p.interval_code='1d' order by p.price_timestamp desc,p.id desc limit 1",Map.of("id",UUID.fromString(security.get("id").toString()))).stream().findFirst().orElse(null));
    value.put("snapshot",read.rows("select "+PRICE_FIELDS+PRICES+"where p.security_id=:id and p.is_canonical and p.interval_code='snapshot' order by p.price_timestamp desc,p.id desc limit 1",Map.of("id",UUID.fromString(security.get("id").toString()))).stream().findFirst().orElse(null));
    return data(value,PRICE_NOTE,"Missing daily/snapshot is null, not zero. A snapshot must not be treated as a completed daily bar.");
  }
  public Data<Map<String,Object>> overview(String exchange,LocalDate asOf) {
    LocalDate date=asOf==null?LocalDate.now(ZONE):asOf;window(date,date,0,0);var args=new HashMap<String,Object>();args.put("until",date.plusDays(1).atStartOfDay(ZONE).toInstant());
    String filter="";if(exchange!=null&&!exchange.isBlank()) {args.put("exchange",code(exchange));filter=" and s.exchange=:exchange";}
    // Lateral bounded lookups use the existing security/timestamp index; no provider call or all-history sort.
    var quotes=read.rows("""
      select s.symbol,s.exchange,s.currency,'SHARES' as "volumeUnit",c.legal_name as "companyName",p.close_price as "close",
      (p.price_timestamp at time zone 'Asia/Ho_Chi_Minh')::date as "tradingDate",p.price_timestamp as "timestamp",p.volume,
      d.code as "sourceCode",p.price_basis as "priceBasis",previous.close_price as "previousClose",
      case when previous.close_price>0 and previous.data_source_id=p.data_source_id and previous.price_basis=p.price_basis and p.price_basis<>'UNKNOWN'
        then round((p.close_price/previous.close_price-1)*100,8) end as "changePercent"
      from securities s join companies c on c.id=s.company_id
      left join lateral (select m.* from market_prices m join data_versions v on v.id=m.data_version_id and v.status='ACTIVATED'
        where m.security_id=s.id and m.is_canonical and m.interval_code='1d' and m.price_timestamp<:until
        order by m.price_timestamp desc,m.id desc limit 1) p on true
      left join data_sources d on d.id=p.data_source_id
      left join lateral (select m.close_price,m.data_source_id,m.price_basis from market_prices m join data_versions v on v.id=m.data_version_id and v.status='ACTIVATED'
        where m.security_id=s.id and m.is_canonical and m.interval_code='1d' and m.price_timestamp<p.price_timestamp
        order by m.price_timestamp desc,m.id desc limit 1) previous on true
      where s.is_active and c.is_active
      """+filter+" order by s.symbol,s.exchange,s.id limit 501",args);
    if(quotes.size()>500) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,"Overview exceeds 500 listings; filter by exchange");
    String latestDate=quotes.stream().map(q->(String)q.get("tradingDate")).filter(Objects::nonNull).max(String::compareTo).orElse(null);
    int rising=0,falling=0,flat=0,unknown=0,stale=0,missing=0;
    for(var quote:quotes) {
      boolean current=latestDate!=null&&latestDate.equals(quote.get("tradingDate"));quote.put("onLatestStoredSession",current);
      if(quote.get("tradingDate")==null) {missing++;continue;}if(!current) {stale++;continue;}
      Object change=quote.get("changePercent");if(change==null) {unknown++;continue;}
      int sign=new java.math.BigDecimal(change.toString()).signum();if(sign>0) rising++;else if(sign<0) falling++;else flat++;
    }
    var result=new LinkedHashMap<String,Object>();result.put("requestedAsOfDate",date);result.put("latestStoredTradingDate",latestDate);result.put("securities",quotes);
    var indices=read.rows("""
      select i.code,i.name,i.exchange,i.currency,'POINTS' as "unit",p.close_value as "close",p.price_timestamp as "timestamp",
      (p.price_timestamp at time zone 'Asia/Ho_Chi_Minh')::date as "tradingDate",d.code as "sourceCode"
      from market_indices i left join lateral (
        select p.* from index_prices p join data_versions v on v.id=p.data_version_id and v.status='ACTIVATED'
        where p.market_index_id=i.id and p.is_canonical and p.interval_code='1d' and p.price_timestamp<:until
        order by p.price_timestamp desc,p.id desc limit 1) p on true
      left join data_sources d on d.id=p.data_source_id where i.is_active order by i.code,i.id limit 101
      """,args);
    result.put("indicesTruncated",indices.size()>100);result.put("indices",indices.subList(0,Math.min(100,indices.size())));
    result.put("breadth",Map.of("rising",rising,"falling",falling,"unchanged",flat,"unknownChange",unknown,"olderSession",stale,"missingPrice",missing));
    return data(result,PRICE_NOTE,"Breadth covers tracked active listings on the latest stored session only, not the entire exchange. Changes require comparable source and price basis.","exchange filters securities only; index cards cover the stored active index catalog, each with its own actual trading date.");
  }
  public Data<Map<String,Object>> filters() {
    var result=new LinkedHashMap<String,Object>();
    result.put("exchanges",read.rows("select s.exchange,count(*) as count "+SECURITY_JOIN+" where s.is_active and c.is_active group by s.exchange order by s.exchange",Map.of()));
    result.put("industries",read.rows("select c.industry_name as name,count(distinct c.id) as count "+SECURITY_JOIN+" where s.is_active and c.is_active and c.industry_name is not null group by c.industry_name order by c.industry_name",Map.of()));
    result.put("statementTypes",List.of("BALANCE_SHEET","INCOME_STATEMENT","CASH_FLOW"));result.put("periodTypes",List.of("FY","Q1","Q2","Q3","Q4"));
    result.put("metricOrigins",List.of("PROVIDER","DERIVED"));return data(result,"Filter counts describe tracked active master records, not the whole exchange.");
  }

  // ---- indices and macro ----
  private Map<String,Object> index(String code) {return read.one("select id,code,name,exchange,currency,is_active as \"active\" from market_indices where code=:code",Map.of("code",code(code)),"Market index");}
  public Page<Map<String,Object>> indices(int page,int size) {
    return read.page("select i.id,i.code,i.name,i.exchange,i.currency,i.is_benchmark as \"benchmark\",i.is_active as \"active\"",
        "from market_indices i where i.is_active","order by i.code,i.id",Map.of(),page,size,100,"Stored index catalog; registration alone does not mean price history exists.");
  }
  public Page<Map<String,Object>> indexPrices(String code,LocalDate from,LocalDate to,int page,int size) {
    var index=index(code);var range=window(from,to,1,15);
    return read.page("""
      select p.id,p.price_timestamp as "timestamp",(p.price_timestamp at time zone 'Asia/Ho_Chi_Minh')::date as "tradingDate",
      p.open_value as "open",p.high_value as "high",p.low_value as "low",p.close_value as "close",p.volume,p.trading_value as "tradingValue",
      p.interval_code as "interval",'POINTS' as "unit",d.code as "sourceCode",p.data_version_id as "versionId"
      ""","from index_prices p join data_sources d on d.id=p.data_source_id join data_versions v on v.id=p.data_version_id and v.status='ACTIVATED' where p.market_index_id=:id and p.is_canonical and p.interval_code='1d' and p.price_timestamp>=:start and p.price_timestamp<:until",
      "order by p.price_timestamp,p.id",Map.of("id",UUID.fromString(index.get("id").toString()),"start",range.start(),"until",range.until()),page,size,500,"Index values are points; stored daily bars only. Missing observations are not interpolated.");
  }
  public Page<Map<String,Object>> members(String code,LocalDate asOf,int page,int size) {
    var index=index(code);LocalDate date=asOf==null?LocalDate.now(ZONE):asOf;window(date,date,0,0);
    return read.page("select s.symbol,s.exchange,c.legal_name as \"companyName\",m.effective_from as \"effectiveFrom\",m.effective_to as \"effectiveTo\",m.weight",
        "from security_index_memberships m join securities s on s.id=m.security_id join companies c on c.id=s.company_id where m.market_index_id=:id and m.effective_from<=:date and (m.effective_to is null or m.effective_to>:date)",
        "order by s.symbol,s.exchange,m.id",Map.of("id",UUID.fromString(index.get("id").toString()),"date",date),page,size,100,"Effective interval is [from,to). Stored membership dates do not certify historical index composition or point-in-time availability.");
  }
  public Page<Map<String,Object>> macroSeries(int page,int size) {
    return read.page("select m.id,m.code,m.name,m.country_code as \"countryCode\",m.frequency,m.unit,m.description,d.code as \"sourceCode\"",
        "from macro_series m join data_sources d on d.id=m.data_source_id where m.is_active","order by m.code,d.code,m.id",Map.of(),page,size,100,"Use series UUID: a series code can exist at several providers.");
  }
  public Page<Map<String,Object>> macro(UUID id,LocalDate from,LocalDate to,int page,int size) {
    read.one("select id from macro_series where id=:id and is_active",Map.of("id",id),"Macro series");var range=window(from,to,10,100);
    return read.page("select o.id,m.code,m.unit,m.frequency,m.country_code as \"countryCode\",o.observation_date as \"date\",o.value,o.value_text as \"valueText\",o.updated_at as \"storedAt\",d.code as \"sourceCode\",o.data_version_id as \"versionId\"",
        "from macro_observations o join macro_series m on m.id=o.macro_series_id join data_sources d on d.id=m.data_source_id join data_versions v on v.id=o.data_version_id and v.status='ACTIVATED' where o.macro_series_id=:id and o.observation_date>=:start and o.observation_date<=:end",
        "order by o.observation_date,o.id",Map.of("id",id,"start",range.from(),"end",range.to()),page,size,500,"Observation dates are economic periods, not verified release/vintage dates. Null values remain missing.");
  }

  // ---- supplementary security observations ----
  private Map<String,Object> observationArgs(String symbol,String exchange,LocalDate from,LocalDate to){
    var range=window(from,to,10,30);var args=new HashMap<String,Object>();args.put("security",UUID.fromString(read.resolve(symbol,exchange).get("id").toString()));
    args.put("from",range.from());args.put("to",range.to());args.put("start",range.start());args.put("until",range.until());return args;
  }
  public Page<Map<String,Object>> foreign(String symbol,String exchange,LocalDate from,LocalDate to,int page,int size){
    return read.page("""
      select f.id,f.trading_date as "tradingDate",f.buy_volume as "buyVolume",f.sell_volume as "sellVolume",
      'SHARES' as "volumeUnit",f.source_observed_at as "sourceObservedAt",f.created_at as "storedAt",d.code as "sourceCode",f.data_version_id as "versionId"
      ""","from security_foreign_observations f join data_sources d on d.id=f.data_source_id join data_versions v on v.id=f.data_version_id and v.status='ACTIVATED' where f.security_id=:security and f.is_canonical and f.trading_date between :from and :to",
      "order by f.trading_date,f.id",observationArgs(symbol,exchange,from,to),page,size,100,"Activated canonical source observations only. Source volume contract is SHARES. Missing buy/sell volume stays null; absence of a day does not mean zero.");
  }
  public Page<Map<String,Object>> reference(String symbol,String exchange,LocalDate from,LocalDate to,int page,int size){
    return read.page("""
      select r.id,r.observed_at as "observedAt",r.source_effective_at as "sourceEffectiveAt",r.industry_name as "industryName",
      r.outstanding_shares as "outstandingShares",r.current_venue_listing_date as "currentVenueListingDate",r.delisted_date as "delistedDate",
      r.listing_status as "listingStatus",d.code as "sourceCode",r.data_version_id as "versionId"
      ""","from security_reference_observations r join data_sources d on d.id=r.data_source_id join data_versions v on v.id=r.data_version_id and v.status='ACTIVATED' where r.security_id=:security and r.observed_at>=:start and r.observed_at<:until",
      "order by r.observed_at desc,r.id",observationArgs(symbol,exchange,from,to),page,size,100,"Observed reference snapshots, not reconstructed historical master data. Filter uses observedAt in Asia/Ho_Chi_Minh. Current venue listing date is not necessarily the original listing date.");
  }

  // ---- lineage ----
  /** Sources for the exact price observation selected in the UI, never a latest-row substitute. */
  public Data<Map<String,Object>> priceLineage(String symbol,String exchange,long id) {
    if(id<1) throw new IllegalArgumentException("Price ID must be positive");
    var security=read.resolve(symbol,exchange);
    var row=read.one("select "+PRICE_FIELDS+"""
      ,v.status as "versionStatus",v.activated_at as "activatedAt",r.fetched_at as "sourceFetchedAt",r.source_url as "sourceUrl"
      """+PRICES+"""
      left join raw_payloads r on r.id=p.raw_payload_id
      where p.id=:id and p.security_id=:security and p.is_canonical and p.interval_code in ('1d','snapshot')
      """,Map.of("id",id,"security",UUID.fromString(security.get("id").toString())),"Published price");
    row.put("sourceUrl",publicSourceUrl(row.get("sourceUrl")));
    row.put("pointInTimeCertified",false);
    return data(row,PRICE_NOTE,"Source belongs to this exact price ID. Daily and snapshot observations stay separate. Raw bodies and request parameters are omitted; unsafe source URLs are null.");
  }
}
