package com.hethongdata.taichinh.service.forecast;

import com.hethongdata.taichinh.dto.forecast.ForecastDtos.*;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Component;

/** Descriptive trailing indicators only: never substitutes a statistical price forecast. */
@Component
public class MarketScenarioCalculator {
  public List<DerivedMetric> calculate(List<SourcePoint> market) {
    var history =
        market.stream()
            .filter(p -> "VND_PER_SHARE".equals(p.unit()))
            .sorted(Comparator.comparing(SourcePoint::periodEnd).reversed())
            .toList();
    var result = new ArrayList<DerivedMetric>();
    for (int sessions : List.of(20, 60)) {
      if (history.size() < sessions) continue;
      var trailing = history.subList(0, sessions);
      BigDecimal mean =
          trailing.stream()
              .map(SourcePoint::value)
              .reduce(BigDecimal.ZERO, BigDecimal::add)
              .divide(BigDecimal.valueOf(sessions), 8, RoundingMode.HALF_UP);
      result.add(
          new DerivedMetric(
              "SMA_" + sessions,
              mean,
              "VND_PER_SHARE",
              "sum(close of last " + sessions + " sessions) / " + sessions,
              trailing.stream().map(SourcePoint::id).toList()));
    }
    if (history.size() > 20) {
      var latest = history.getFirst();
      var previous = history.get(20);
      if (previous.value().signum() > 0)
        result.add(
            new DerivedMetric(
                "PRICE_RETURN_20_SESSIONS",
                latest
                    .value()
                    .divide(previous.value(), 12, RoundingMode.HALF_UP)
                    .subtract(BigDecimal.ONE)
                    .multiply(BigDecimal.valueOf(100)),
                "%",
                "(latest close / close 20 sessions earlier - 1) * 100",
                List.of(latest.id(), previous.id())));
    }
    return List.copyOf(result);
  }
}
