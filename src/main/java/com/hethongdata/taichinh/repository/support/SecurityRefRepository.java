package com.hethongdata.taichinh.repository.support;

import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class SecurityRefRepository {
  public static final String SECURITY_FIELDS = """
    s.id as "id",s.symbol,s.exchange,s.security_type as "securityType",s.currency,
    s.is_active as "active",s.is_primary as "primary",s.listed_date as "listedDate",s.delisted_date as "delistedDate",
    s.shares_outstanding as "sharesOutstanding",s.par_value as "parValue",s.company_id as "companyId",
    c.legal_name as "companyName",c.short_name as "shortName",c.industry_name as "industryName",c.sector_name as "sectorName"
    """;
  public static final String SECURITY_JOIN = " from securities s join companies c on c.id=s.company_id ";

  private final NamedParameterJdbcTemplate db;

  public SecurityRefRepository(NamedParameterJdbcTemplate db) {
    this.db = db;
  }

  public static String code(String value) {
    if (value == null || !value.trim().matches("[A-Za-z0-9][A-Za-z0-9._-]{0,39}"))
      throw new IllegalArgumentException("Invalid code or symbol");
    return value.trim().toUpperCase(Locale.ROOT);
  }

  public Map<String, Object> resolve(String symbol, String exchange) {
    var args = new HashMap<String, Object>();
    args.put("symbol", code(symbol));
    String where = "where s.symbol=:symbol";
    if (exchange != null && !exchange.isBlank()) {
      where += " and s.exchange=:exchange";
      args.put("exchange", code(exchange));
    }
    var rows = db.query("select " + SECURITY_FIELDS + SECURITY_JOIN + where, args, (rs, n) -> {
      var row = new LinkedHashMap<String, Object>();
      var md = rs.getMetaData();
      for (int i = 1; i <= md.getColumnCount(); i++) {
        row.put(md.getColumnLabel(i), rs.getObject(i));
      }
      return row;
    });
    if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Security not found");
    if (rows.size() > 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Security is ambiguous; specify exchange");
    return rows.getFirst();
  }
}
