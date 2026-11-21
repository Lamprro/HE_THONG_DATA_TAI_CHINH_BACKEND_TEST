package com.hethongdata.taichinh.service.user;

import com.hethongdata.taichinh.repository.jdbc.user.UserMarketQueryRepository;
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
  private final UserMarketQueryRepository marketRepo;
  private final UserReadSupport read;

  public UserMarketService(UserMarketQueryRepository marketRepo, UserReadSupport read) {
    this.marketRepo = marketRepo;
    this.read = read;
  }

  static final String PRICE_NOTE="Stored canonical prices, not realtime. Gaps are not filled; nominal close changes are not total returns or adjusted returns.";

  // ---- prices and market overview ----
  public Page<Map<String,Object>> prices(String symbol,String exchange,String interval,LocalDate from,LocalDate to,int page,int size) {
    var security = read.resolve(symbol, exchange);
    String frame = option(interval == null ? "1D" : interval, Set.of("1D", "SNAPSHOT")).toLowerCase(Locale.ROOT);
    var window = window(from, to, 1, 15);
    var securityId = UUID.fromString(security.get("id").toString());
    return marketRepo.queryPrices(securityId, frame, window.start(), window.until(), page, size, PRICE_NOTE);
  }

  public Data<Map<String,Object>> latest(String symbol,String exchange) {
    var security = read.resolve(symbol, exchange);
    var securityId = UUID.fromString(security.get("id").toString());
    var value = new LinkedHashMap<String, Object>();
    value.put("security", security);
    value.put("daily", marketRepo.findLatestPrice(securityId, "1d"));
    value.put("snapshot", marketRepo.findLatestPrice(securityId, "snapshot"));
    return data(value, PRICE_NOTE, "Missing daily/snapshot is null, not zero. A snapshot must not be treated as a completed daily bar.");
  }

  public Data<Map<String,Object>> overview(String exchange,LocalDate asOf) {
    LocalDate date = asOf == null ? LocalDate.now(ZONE) : asOf;
    window(date, date, 0, 0);
    Instant until = date.plusDays(1).atStartOfDay(ZONE).toInstant();
    String exchangeCode = (exchange != null && !exchange.isBlank()) ? code(exchange) : null;

    var quotes = marketRepo.findOverviewQuotes(exchangeCode, until);
    if (quotes.size() > 500) {
      throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY, "Overview exceeds 500 listings; filter by exchange");
    }
    String latestDate = quotes.stream().map(q -> (String) q.get("tradingDate")).filter(Objects::nonNull).max(String::compareTo).orElse(null);
    int rising = 0, falling = 0, flat = 0, unknown = 0, stale = 0, missing = 0;
    for (var quote : quotes) {
      boolean current = latestDate != null && latestDate.equals(quote.get("tradingDate"));
      quote.put("onLatestStoredSession", current);
      if (quote.get("tradingDate") == null) {
        missing++;
        continue;
      }
      if (!current) {
        stale++;
        continue;
      }
      Object change = quote.get("changePercent");
      if (change == null) {
        unknown++;
        continue;
      }
      int sign = new java.math.BigDecimal(change.toString()).signum();
      if (sign > 0) rising++;
      else if (sign < 0) falling++;
      else flat++;
    }
    var result = new LinkedHashMap<String, Object>();
    result.put("requestedAsOfDate", date);
    result.put("latestStoredTradingDate", latestDate);
    result.put("securities", quotes);

    var indices = marketRepo.findOverviewIndices(until);
    result.put("indicesTruncated", indices.size() > 100);
    result.put("indices", indices.subList(0, Math.min(100, indices.size())));
    result.put("breadth", Map.of("rising", rising, "falling", falling, "unchanged", flat, "unknownChange", unknown, "olderSession", stale, "missingPrice", missing));
    return data(result, PRICE_NOTE, "Breadth covers tracked active listings on the latest stored session only, not the entire exchange. Changes require comparable source and price basis.", "exchange filters securities only; index cards cover the stored active index catalog, each with its own actual trading date.");
  }

  public Data<Map<String,Object>> filters() {
    var result = new LinkedHashMap<String, Object>();
    result.put("exchanges", marketRepo.findFilterExchanges());
    result.put("industries", marketRepo.findFilterIndustries());
    result.put("statementTypes", List.of("BALANCE_SHEET", "INCOME_STATEMENT", "CASH_FLOW"));
    result.put("periodTypes", List.of("FY", "Q1", "Q2", "Q3", "Q4"));
    result.put("metricOrigins", List.of("PROVIDER", "DERIVED"));
    return data(result, "Filter counts describe tracked active master records, not the whole exchange.");
  }

  // ---- indices and macro ----
  private Map<String,Object> index(String code) {
    return marketRepo.findIndex(code(code));
  }

  public Page<Map<String,Object>> indices(int page,int size) {
    return marketRepo.queryIndices(page, size);
  }

  public Page<Map<String,Object>> indexPrices(String code,LocalDate from,LocalDate to,int page,int size) {
    var index = index(code);
    var range = window(from, to, 1, 15);
    var indexId = UUID.fromString(index.get("id").toString());
    return marketRepo.queryIndexPrices(indexId, range.start(), range.until(), page, size);
  }

  public Page<Map<String,Object>> members(String code,LocalDate asOf,int page,int size) {
    var index = index(code);
    LocalDate date = asOf == null ? LocalDate.now(ZONE) : asOf;
    window(date, date, 0, 0);
    var indexId = UUID.fromString(index.get("id").toString());
    return marketRepo.queryMembers(indexId, date, page, size);
  }

  public Page<Map<String,Object>> macroSeries(int page,int size) {
    return marketRepo.queryMacroSeries(page, size);
  }

  public Page<Map<String,Object>> macro(UUID id,LocalDate from,LocalDate to,int page,int size) {
    marketRepo.verifyMacroSeries(id);
    var range = window(from, to, 10, 100);
    return marketRepo.queryMacroObservations(id, range.from(), range.to(), page, size);
  }

  // ---- supplementary security observations ----
  public Page<Map<String,Object>> foreign(String symbol,String exchange,LocalDate from,LocalDate to,int page,int size) {
    var range = window(from, to, 10, 30);
    var securityId = UUID.fromString(read.resolve(symbol, exchange).get("id").toString());
    return marketRepo.queryForeign(securityId, range.from(), range.to(), page, size);
  }

  public Page<Map<String,Object>> reference(String symbol,String exchange,LocalDate from,LocalDate to,int page,int size) {
    var range = window(from, to, 10, 30);
    var securityId = UUID.fromString(read.resolve(symbol, exchange).get("id").toString());
    return marketRepo.queryReference(securityId, range.start(), range.until(), page, size);
  }

  // ---- lineage ----
  /** Sources for the exact price observation selected in the UI, never a latest-row substitute. */
  public Data<Map<String,Object>> priceLineage(String symbol,String exchange,long id) {
    if (id < 1) throw new IllegalArgumentException("Price ID must be positive");
    var security = read.resolve(symbol, exchange);
    var securityId = UUID.fromString(security.get("id").toString());
    var row = marketRepo.findPriceLineage(id, securityId);
    row.put("sourceUrl", publicSourceUrl(row.get("sourceUrl")));
    row.put("pointInTimeCertified", false);
    return data(row, PRICE_NOTE, "Source belongs to this exact price ID. Daily and snapshot observations stay separate. Raw bodies and request parameters are omitted; unsafe source URLs are null.");
  }
}
