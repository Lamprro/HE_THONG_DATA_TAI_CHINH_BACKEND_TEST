package com.hethongdata.taichinh.repository.jdbc.user;

import com.hethongdata.taichinh.service.user.UserReadSupport;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Repository;

@Repository
public class UserMarketQueryRepository {
  public static final String PRICE_FIELDS = """
    p.id,p.price_timestamp as "timestamp",(p.price_timestamp at time zone 'Asia/Ho_Chi_Minh')::date as "tradingDate",
    p.interval_code as "interval",p.open_price as "open",p.high_price as "high",p.low_price as "low",p.close_price as "close",
    p.volume,p.trading_value as "tradingValue",p.price_basis as "priceBasis",p.updated_at as "storedAt",
    d.code as "sourceCode",d.name as "sourceName",p.data_version_id as "versionId",security.currency,'SHARES' as "volumeUnit"
    """;

  public static final String PRICES = " from market_prices p join securities security on security.id=p.security_id join data_sources d on d.id=p.data_source_id join data_versions v on v.id=p.data_version_id and v.status='ACTIVATED' ";

  private final UserReadJdbcRepository read;

  public UserMarketQueryRepository(UserReadJdbcRepository read) {
    this.read = read;
  }

  public UserReadSupport.Page<Map<String, Object>> queryPrices(
      UUID securityId, String interval, Instant start, Instant until, int page, int size, String note) {
    var args = new HashMap<String, Object>();
    args.put("id", securityId);
    args.put("interval", interval);
    args.put("start", start);
    args.put("until", until);
    return read.page(
        "select " + PRICE_FIELDS,
        PRICES + "where p.security_id=:id and p.is_canonical and p.interval_code=:interval and p.price_timestamp>=:start and p.price_timestamp<:until",
        "order by p.price_timestamp,p.id",
        args,
        page,
        size,
        500,
        note,
        "Daily and snapshot series are separate. Amounts use the security currency; volume is shares.");
  }

  public Map<String, Object> findLatestPrice(UUID securityId, String interval) {
    var rows = read.rows(
        "select " + PRICE_FIELDS + PRICES
            + "where p.security_id=:id and p.is_canonical and p.interval_code=:interval order by p.price_timestamp desc,p.id desc limit 1",
        Map.of("id", securityId, "interval", interval));
    return rows.stream().findFirst().orElse(null);
  }

  public List<Map<String, Object>> findOverviewQuotes(String exchange, Instant until) {
    var args = new HashMap<String, Object>();
    args.put("until", until);
    String filter = "";
    if (exchange != null && !exchange.isBlank()) {
      args.put("exchange", exchange);
      filter = " and s.exchange=:exchange";
    }
    return read.rows(
        """
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
        """ + filter + " order by s.symbol,s.exchange,s.id limit 501",
        args);
  }

  public List<Map<String, Object>> findOverviewIndices(Instant until) {
    return read.rows(
        """
        select i.code,i.name,i.exchange,i.currency,'POINTS' as "unit",p.close_value as "close",p.price_timestamp as "timestamp",
        (p.price_timestamp at time zone 'Asia/Ho_Chi_Minh')::date as "tradingDate",d.code as "sourceCode"
        from market_indices i left join lateral (
          select p.* from index_prices p join data_versions v on v.id=p.data_version_id and v.status='ACTIVATED'
          where p.market_index_id=i.id and p.is_canonical and p.interval_code='1d' and p.price_timestamp<:until
          order by p.price_timestamp desc,p.id desc limit 1) p on true
        left join data_sources d on d.id=p.data_source_id where i.is_active order by i.code,i.id limit 101
        """,
        Map.of("until", until));
  }

  public List<Map<String, Object>> findFilterExchanges() {
    return read.rows(
        "select s.exchange,count(*) as count " + UserReadJdbcRepository.SECURITY_JOIN
            + " where s.is_active and c.is_active group by s.exchange order by s.exchange",
        Map.of());
  }

  public List<Map<String, Object>> findFilterIndustries() {
    return read.rows(
        "select c.industry_name as name,count(distinct c.id) as count " + UserReadJdbcRepository.SECURITY_JOIN
            + " where s.is_active and c.is_active and c.industry_name is not null group by c.industry_name order by c.industry_name",
        Map.of());
  }

  public Map<String, Object> findIndex(String code) {
    return read.one(
        "select id,code,name,exchange,currency,is_active as \"active\" from market_indices where code=:code",
        Map.of("code", code),
        "Market index");
  }

  public UserReadSupport.Page<Map<String, Object>> queryIndices(int page, int size) {
    return read.page(
        "select i.id,i.code,i.name,i.exchange,i.currency,i.is_benchmark as \"benchmark\",i.is_active as \"active\"",
        "from market_indices i where i.is_active",
        "order by i.code,i.id",
        Map.of(),
        page,
        size,
        100,
        "Stored index catalog; registration alone does not mean price history exists.");
  }

  public UserReadSupport.Page<Map<String, Object>> queryIndexPrices(
      UUID indexId, Instant start, Instant until, int page, int size) {
    return read.page(
        """
        select p.id,p.price_timestamp as "timestamp",(p.price_timestamp at time zone 'Asia/Ho_Chi_Minh')::date as "tradingDate",
        p.open_value as "open",p.high_value as "high",p.low_value as "low",p.close_value as "close",p.volume,p.trading_value as "tradingValue",
        p.interval_code as "interval",'POINTS' as "unit",d.code as "sourceCode",p.data_version_id as "versionId"
        """,
        "from index_prices p join data_sources d on d.id=p.data_source_id join data_versions v on v.id=p.data_version_id and v.status='ACTIVATED' where p.market_index_id=:id and p.is_canonical and p.interval_code='1d' and p.price_timestamp>=:start and p.price_timestamp<:until",
        "order by p.price_timestamp,p.id",
        Map.of("id", indexId, "start", start, "until", until),
        page,
        size,
        500,
        "Index values are points; stored daily bars only. Missing observations are not interpolated.");
  }

  public UserReadSupport.Page<Map<String, Object>> queryMembers(UUID indexId, LocalDate date, int page, int size) {
    return read.page(
        "select s.symbol,s.exchange,c.legal_name as \"companyName\",m.effective_from as \"effectiveFrom\",m.effective_to as \"effectiveTo\",m.weight",
        "from security_index_memberships m join securities s on s.id=m.security_id join companies c on c.id=s.company_id where m.market_index_id=:id and m.effective_from<=:date and (m.effective_to is null or m.effective_to>:date)",
        "order by s.symbol,s.exchange,m.id",
        Map.of("id", indexId, "date", date),
        page,
        size,
        100,
        "Effective interval is [from,to). Stored membership dates do not certify historical index composition or point-in-time availability.");
  }

  public UserReadSupport.Page<Map<String, Object>> queryMacroSeries(int page, int size) {
    return read.page(
        "select m.id,m.code,m.name,m.country_code as \"countryCode\",m.frequency,m.unit,m.description,d.code as \"sourceCode\"",
        "from macro_series m join data_sources d on d.id=m.data_source_id where m.is_active",
        "order by m.code,d.code,m.id",
        Map.of(),
        page,
        size,
        100,
        "Use series UUID: a series code can exist at several providers.");
  }

  public void verifyMacroSeries(UUID id) {
    read.one("select id from macro_series where id=:id and is_active", Map.of("id", id), "Macro series");
  }

  public UserReadSupport.Page<Map<String, Object>> queryMacroObservations(
      UUID id, LocalDate start, LocalDate end, int page, int size) {
    return read.page(
        "select o.id,m.code,m.unit,m.frequency,m.country_code as \"countryCode\",o.observation_date as \"date\",o.value,o.value_text as \"valueText\",o.updated_at as \"storedAt\",d.code as \"sourceCode\",o.data_version_id as \"versionId\"",
        "from macro_observations o join macro_series m on m.id=o.macro_series_id join data_sources d on d.id=m.data_source_id join data_versions v on v.id=o.data_version_id and v.status='ACTIVATED' where o.macro_series_id=:id and o.observation_date>=:start and o.observation_date<=:end",
        "order by o.observation_date,o.id",
        Map.of("id", id, "start", start, "end", end),
        page,
        size,
        500,
        "Observation dates are economic periods, not verified release/vintage dates. Null values remain missing.");
  }

  public UserReadSupport.Page<Map<String, Object>> queryForeign(
      UUID securityId, LocalDate from, LocalDate to, int page, int size) {
    return read.page(
        """
        select f.id,f.trading_date as "tradingDate",f.buy_volume as "buyVolume",f.sell_volume as "sellVolume",
        'SHARES' as "volumeUnit",f.source_observed_at as "sourceObservedAt",f.created_at as "storedAt",d.code as "sourceCode",f.data_version_id as "versionId"
        """,
        "from security_foreign_observations f join data_sources d on d.id=f.data_source_id join data_versions v on v.id=f.data_version_id and v.status='ACTIVATED' where f.security_id=:security and f.is_canonical and f.trading_date between :from and :to",
        "order by f.trading_date,f.id",
        Map.of("security", securityId, "from", from, "to", to),
        page,
        size,
        100,
        "Activated canonical source observations only. Source volume contract is SHARES. Missing buy/sell volume stays null; absence of a day does not mean zero.");
  }

  public UserReadSupport.Page<Map<String, Object>> queryReference(
      UUID securityId, Instant start, Instant until, int page, int size) {
    return read.page(
        """
        select r.id,r.observed_at as "observedAt",r.source_effective_at as "sourceEffectiveAt",r.industry_name as "industryName",
        r.outstanding_shares as "outstandingShares",r.current_venue_listing_date as "currentVenueListingDate",r.delisted_date as "delistedDate",
        r.listing_status as "listingStatus",d.code as "sourceCode",r.data_version_id as "versionId"
        """,
        "from security_reference_observations r join data_sources d on d.id=r.data_source_id join data_versions v on v.id=r.data_version_id and v.status='ACTIVATED' where r.security_id=:security and r.observed_at>=:start and r.observed_at<:until",
        "order by r.observed_at desc,r.id",
        Map.of("security", securityId, "start", start, "until", until),
        page,
        size,
        100,
        "Observed reference snapshots, not reconstructed historical master data. Filter uses observedAt in Asia/Ho_Chi_Minh. Current venue listing date is not necessarily the original listing date.");
  }

  public Map<String, Object> findPriceLineage(long id, UUID securityId) {
    return read.one(
        "select " + PRICE_FIELDS + """
        ,v.status as "versionStatus",v.activated_at as "activatedAt",r.fetched_at as "sourceFetchedAt",r.source_url as "sourceUrl"
        """ + PRICES + """
        left join raw_payloads r on r.id=p.raw_payload_id
        where p.id=:id and p.security_id=:security and p.is_canonical and p.interval_code in ('1d','snapshot')
        """,
        Map.of("id", id, "security", securityId),
        "Published price");
  }
}
