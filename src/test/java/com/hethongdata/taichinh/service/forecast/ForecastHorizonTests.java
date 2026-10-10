package com.hethongdata.taichinh.service.forecast;

import static org.assertj.core.api.Assertions.*;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ForecastHorizonTests {
  @Test
  void nextQuarterIsAfterCurrentQuarterAndDistinctOnQuarterEnd() {
    for (String date : new String[] {"2026-10-09", "2026-12-31"}) {
      var asOf = LocalDate.parse(date);
      assertThat(ForecastHorizon.endOfFutureQuarter(asOf, 1)).isEqualTo("2027-03-31");
      assertThat(ForecastHorizon.endOfFutureQuarter(asOf, 2)).isEqualTo("2027-06-30");
    }
  }

  @Test
  void leapYearAndYearBoundaryNeverInventTradingDays() {
    assertThat(ForecastHorizon.endOfFutureQuarter(LocalDate.of(2024, 2, 29), 1))
        .isEqualTo("2024-06-30");
    assertThat(ForecastHorizon.endOfFutureQuarter(LocalDate.of(2026, 3, 31), 8))
        .isEqualTo("2028-03-31");
    assertThatThrownBy(() -> ForecastHorizon.endOfFutureQuarter(LocalDate.of(2026, 3, 31), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
