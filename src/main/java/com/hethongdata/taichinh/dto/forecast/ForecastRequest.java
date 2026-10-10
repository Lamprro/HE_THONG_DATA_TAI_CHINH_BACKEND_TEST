package com.hethongdata.taichinh.dto.forecast;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;

public record ForecastRequest(
    @NotNull UUID securityId,
    @NotNull LocalDate asOfDate,
    @Min(1) @Max(8) int horizonQuarters,
    @NotEmpty @Size(max = 6) Set<Target> targets,
    boolean requireMacro) {
  public enum Target {
    NET_PROFIT_AFTER_TAX,
    PRETAX_PROFIT,
    TOTAL_ASSETS,
    OWNERS_EQUITY,
    LIABILITIES,
    STOCK_PRICE;

    public boolean matches(String domain, String code) {
      if (this == STOCK_PRICE) return "MARKET".equals(domain) && "CLOSE".equals(code);
      String expectedDomain =
          this == NET_PROFIT_AFTER_TAX || this == PRETAX_PROFIT
              ? "INCOME_STATEMENT"
              : "BALANCE_SHEET";
      return expectedDomain.equals(domain) && name().equals(code);
    }
  }
}
