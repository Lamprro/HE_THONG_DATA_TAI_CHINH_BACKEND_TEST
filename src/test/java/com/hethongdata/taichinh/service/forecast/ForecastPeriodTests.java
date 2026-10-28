package com.hethongdata.taichinh.service.forecast;

import static org.assertj.core.api.Assertions.assertThat;
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
}
