package com.hethongdata.taichinh.repository.jdbc.user;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hethongdata.taichinh.service.user.UserReadSupport;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class UserReadJdbcRepository {
  public static final String SECURITY_FIELDS = """
    s.id as "id",s.symbol,s.exchange,s.security_type as "securityType",s.currency,
    s.is_active as "active",s.is_primary as "primary",s.listed_date as "listedDate",s.delisted_date as "delistedDate",
    s.shares_outstanding as "sharesOutstanding",s.par_value as "parValue",s.company_id as "companyId",
    c.legal_name as "companyName",c.short_name as "shortName",c.industry_name as "industryName",c.sector_name as "sectorName"
    """;
  public static final String SECURITY_JOIN = " from securities s join companies c on c.id=s.company_id ";

  private final NamedParameterJdbcTemplate db;
  private final ObjectMapper json;

  @org.springframework.beans.factory.annotation.Autowired
  public UserReadJdbcRepository(JdbcTemplate source, ObjectMapper json) {
    var bounded = new JdbcTemplate(Objects.requireNonNull(source.getDataSource()));
    bounded.setQueryTimeout(10);
    this.db = new NamedParameterJdbcTemplate(bounded);
    this.json = json;
  }

  public UserReadJdbcRepository(NamedParameterJdbcTemplate db, ObjectMapper json) {
    this.db = db;
    this.json = json;
  }

  public Map<String, Object> resolve(String symbol, String exchange) {
    var args = new HashMap<String, Object>();
    args.put("symbol", UserReadSupport.code(symbol));
    String where = "where s.symbol=:symbol";
    if (exchange != null && !exchange.isBlank()) {
      where += " and s.exchange=:exchange";
      args.put("exchange", UserReadSupport.code(exchange));
    }
    return one("select " + SECURITY_FIELDS + SECURITY_JOIN + where, args, "Security");
  }

  public boolean hasColumns(String table, Set<String> required) {
    var columns = rows(
        "select column_name as name from information_schema.columns where table_schema=current_schema() and table_name=:table",
        Map.of("table", table));
    return columns.stream()
        .map(r -> r.get("name").toString())
        .collect(java.util.stream.Collectors.toSet())
        .containsAll(required);
  }

  public List<Map<String, Object>> rows(String sql, Map<String, ?> params) {
    return db.query(sql, parameters(params), (rs, n) -> {
      var row = new LinkedHashMap<String, Object>();
      var columns = rs.getMetaData();
      for (int i = 1; i <= columns.getColumnCount(); i++) {
        row.put(columns.getColumnLabel(i), formatValue(rs.getObject(i)));
      }
      return row;
    });
  }

  public Map<String, Object> one(String sql, Map<String, ?> params, String label) {
    var list = rows(sql, params);
    if (list.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, label + " not found");
    if (list.size() > 1) throw new ResponseStatusException(HttpStatus.CONFLICT, label + " is ambiguous; specify exchange");
    return list.getFirst();
  }

  public UserReadSupport.Page<Map<String, Object>> page(
      String selectCols, String fromWhere, String order, Map<String, ?> params,
      int page, int size, int max, String... notes) {
    UserReadSupport.paging(page, size, max);
    var args = new HashMap<String, Object>(params);
    args.put("limit", size);
    args.put("offset", (long) page * size);
    long total = Objects.requireNonNull(
        db.queryForObject("select count(*) " + fromWhere, parameters(params), Long.class));
    return new UserReadSupport.Page<>(
        rows(selectCols + " " + fromWhere + " " + order + " limit :limit offset :offset", args),
        total, page, size, ((long) page + 1) * size < total, UserReadSupport.meta(notes));
  }

  private Map<String, Object> parameters(Map<String, ?> params) {
    var result = new HashMap<String, Object>();
    params.forEach((key, value) ->
        result.put(key, value instanceof java.time.Instant t ? Timestamp.from(t) : value));
    return result;
  }

  private Object formatValue(Object value) {
    if (value instanceof BigDecimal n) return n.toPlainString();
    if (value instanceof BigInteger || value instanceof Long || value instanceof UUID) return value.toString();
    if (value instanceof Timestamp t) return t.toInstant().toString();
    if (value instanceof java.sql.Date d) return d.toLocalDate().toString();
    if (value instanceof OffsetDateTime t) return t.toInstant().toString();
    return value;
  }
}
