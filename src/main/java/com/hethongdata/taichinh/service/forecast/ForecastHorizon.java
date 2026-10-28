package com.hethongdata.taichinh.service.forecast;

import java.time.LocalDate;
import java.time.YearMonth;

/** Horizon one always means the end of the next calendar quarter, including on quarter-end. */
public final class ForecastHorizon {
  private ForecastHorizon() {}

  public static LocalDate periodStart(LocalDate end) {
    return LocalDate.of(end.getYear(), ((end.getMonthValue() - 1) / 3) * 3 + 1, 1);
  }

  public static LocalDate resolve(LocalDate asOf, int quarters, LocalDate explicitEnd) {
    LocalDate maximum = endOfFutureQuarter(asOf, 8);
    if (explicitEnd == null) return endOfFutureQuarter(asOf, quarters);
    if (explicitEnd.isBefore(asOf) || explicitEnd.isAfter(maximum)
        || explicitEnd.getMonthValue() % 3 != 0
        || !explicitEnd.equals(YearMonth.from(explicitEnd).atEndOfMonth()))
      throw new IllegalArgumentException("forecastPeriodEnd must be a current/future calendar quarter end within eight quarters");
    return explicitEnd;
  }

  public static LocalDate endOfFutureQuarter(LocalDate asOf, int quarters) {
    if (asOf == null || quarters < 1 || quarters > 8)
      throw new IllegalArgumentException("Choose a date and 1-8 future quarters");
    int quarterEndMonth = ((asOf.getMonthValue() - 1) / 3 + 1) * 3;
    return YearMonth.of(asOf.getYear(), quarterEndMonth).plusMonths(quarters * 3L).atEndOfMonth();
  }
}
