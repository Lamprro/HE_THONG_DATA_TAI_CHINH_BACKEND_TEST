package com.hethongdata.taichinh.service.forecast;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class ForecastPeriodTests {
  @Test
  void quarterEndHorizonsAreDistinctAcrossYears() {
    var asOf = LocalDate.of(2026, 12, 31);
    assertThat(ForecastContextService.targetDate(asOf, 1)).isEqualTo(LocalDate.of(2027, 3, 31));
    assertThat(ForecastContextService.targetDate(asOf, 2)).isEqualTo(LocalDate.of(2027, 6, 30));
    assertThat(ForecastContextService.targetDate(asOf, 3)).isEqualTo(LocalDate.of(2027, 9, 30));
  }

  @Test
  void firstHorizonIsTheNextQuarterEndStrictlyAfterAsOf() {
    assertThat(ForecastContextService.targetDate(LocalDate.of(2024, 2, 29), 1)).isEqualTo(LocalDate.of(2024, 3, 31));
    assertThat(ForecastContextService.targetDate(LocalDate.of(2024, 3, 31), 1)).isEqualTo(LocalDate.of(2024, 6, 30));
    assertThat(ForecastContextService.targetDate(LocalDate.of(2024, 3, 31), 2)).isEqualTo(LocalDate.of(2024, 9, 30));
  }

  @Test void explicitQuarterStaysFixedWhenDataCutoffMoves() {
    var end = LocalDate.of(2027,3,31);
    assertThat(ForecastContextService.resolveTargetDate(LocalDate.of(2027,1,1),1,end)).isEqualTo(end);
    assertThat(ForecastContextService.resolveTargetDate(LocalDate.of(2027,2,1),1,end)).isEqualTo(end);
    assertThat(ForecastContextService.periodStart(end)).isEqualTo(LocalDate.of(2027,1,1));
    assertThatThrownBy(() -> ForecastContextService.resolveTargetDate(LocalDate.of(2027,4,1),1,end)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ForecastContextService.resolveTargetDate(LocalDate.of(2027,1,1),1,LocalDate.of(2027,2,28))).isInstanceOf(IllegalArgumentException.class);
  }
  @Test
  void nextQuarterIsAfterCurrentQuarterAndDistinctOnQuarterEnd() {
    for (String date : new String[] {"2026-10-09", "2026-12-31"}) {
      var asOf = LocalDate.parse(date);
      assertThat(ForecastContextService.endOfFutureQuarter(asOf, 1)).isEqualTo("2027-03-31");
      assertThat(ForecastContextService.endOfFutureQuarter(asOf, 2)).isEqualTo("2027-06-30");
    }
  }

  @Test
  void leapYearAndYearBoundaryNeverInventTradingDays() {
    assertThat(ForecastContextService.endOfFutureQuarter(LocalDate.of(2024, 2, 29), 1))
        .isEqualTo("2024-06-30");
    assertThat(ForecastContextService.endOfFutureQuarter(LocalDate.of(2026, 3, 31), 8))
        .isEqualTo("2028-03-31");
    assertThatThrownBy(() -> ForecastContextService.endOfFutureQuarter(LocalDate.of(2026, 3, 31), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
