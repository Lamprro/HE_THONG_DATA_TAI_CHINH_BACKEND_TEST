package com.hethongdata.taichinh.service.financial;

import com.hethongdata.taichinh.dto.forecast.ForecastDtos.*;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * Balance-sheet ratios only; no invented debt, revenue mapping or unverified EPS/share-price
 * scaling.
 */
@Component
public class FinancialRatioCalculator {
  public List<DerivedMetric> calculate(List<SourcePoint> points) {
    var output = new ArrayList<DerivedMetric>();
    var periods = new TreeMap<String, List<SourcePoint>>();
    points.stream()
        .filter(p -> p.domain().equals("BALANCE_SHEET"))
        .forEach(
            p ->
                periods
                    .computeIfAbsent(p.periodEnd() + ":" + p.scope(), k -> new ArrayList<>())
                    .add(p));
    for (var group : periods.values()) {
      var index = new HashMap<String, SourcePoint>();
      var duplicates = new HashSet<String>();
      for (var p : group) if (index.putIfAbsent(p.code(), p) != null) duplicates.add(p.code());
      duplicates.forEach(index::remove);
      ratio(
          output,
          index,
          "LIABILITIES_TO_EQUITY",
          "LIABILITIES",
          "OWNERS_EQUITY",
          BigDecimal.ONE,
          "x");
      ratio(
          output,
          index,
          "LIABILITIES_TO_ASSETS",
          "LIABILITIES",
          "TOTAL_ASSETS",
          new BigDecimal("100"),
          "%");
      ratio(
          output,
          index,
          "EQUITY_TO_ASSETS",
          "OWNERS_EQUITY",
          "TOTAL_ASSETS",
          new BigDecimal("100"),
          "%");
      ratio(
          output,
          index,
          "CURRENT_RATIO",
          "CURRENT_ASSETS",
          "SHORT_TERM_LIABILITIES",
          BigDecimal.ONE,
          "x");
      ratio(
          output,
          index,
          "CASH_RATIO",
          "CASH_AND_CASH_EQUIVALENTS",
          "SHORT_TERM_LIABILITIES",
          BigDecimal.ONE,
          "x");
    }
    return List.copyOf(output);
  }

  private void ratio(
      List<DerivedMetric> out,
      Map<String, SourcePoint> points,
      String code,
      String numerator,
      String denominator,
      BigDecimal factor,
      String unit) {
    var a = points.get(numerator);
    var b = points.get(denominator);
    if (a == null
        || b == null
        || a.value() == null
        || b.value() == null
        || b.value().signum() <= 0
        || a.value().signum() < 0
        || !a.unit().equals(b.unit())) return;
    out.add(
        new DerivedMetric(
            code,
            a.value().multiply(factor).divide(b.value(), 8, RoundingMode.HALF_UP),
            unit,
            numerator + " / " + denominator + (factor.equals(BigDecimal.ONE) ? "" : " * 100"),
            List.of(a.id(), b.id())));
  }
}
