package com.hethongdata.taichinh.service.forecast;

import java.time.LocalDate;
import java.time.YearMonth;

/** Horizon one always means the end of the next calendar quarter, including on quarter-end. */
public final class ForecastHorizon {
  private ForecastHorizon() {}

  public static LocalDate endOfFutureQuarter(LocalDate asOf, int quarters) {
    if (asOf == null || quarters < 1 || quarters > 8)
      throw new IllegalArgumentException("Choose a date and 1-8 future quarters");
    int quarterEndMonth = ((asOf.getMonthValue() - 1) / 3 + 1) * 3;
    return YearMonth.of(asOf.getYear(), quarterEndMonth).plusMonths(quarters * 3L).atEndOfMonth();
  }
}
