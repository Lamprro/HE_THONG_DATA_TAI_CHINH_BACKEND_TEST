package com.hethongdata.taichinh.service.forecast;

import static org.assertj.core.api.Assertions.*;

import com.hethongdata.taichinh.dto.forecast.ForecastDtos.SourcePoint;
import com.hethongdata.taichinh.service.financial.FinancialRatioCalculator;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;

class FinancialRatioCalculatorTests {
  final FinancialRatioCalculator calculator = new FinancialRatioCalculator();

  SourcePoint point(String id, String code, String amount, String unit, LocalDate period) {
    return new SourcePoint(
        id,
        "BALANCE_SHEET",
        code,
        new BigDecimal(amount),
        unit,
        period.minusMonths(3).plusDays(1),
        period,
        "Q2",
        "UNKNOWN",
        "2026-09-26");
  }

  @Test
  void computesLiabilitiesAndEquityRatiosWithProvenance() {
    var date = LocalDate.of(2026, 6, 30);
    var rows =
        calculator.calculate(
            List.of(
                point("a", "LIABILITIES", "60", "VND", date),
                point("b", "TOTAL_ASSETS", "100", "VND", date),
                point("c", "OWNERS_EQUITY", "40", "VND", date)));
    assertThat(rows).hasSize(3);
    assertThat(
            rows.stream()
                .filter(r -> r.code().equals("LIABILITIES_TO_EQUITY"))
                .findFirst()
                .orElseThrow()
                .value())
        .isEqualByComparingTo("1.5");
    assertThat(
            rows.stream()
                .filter(r -> r.code().equals("EQUITY_TO_ASSETS"))
                .findFirst()
                .orElseThrow()
                .value())
        .isEqualByComparingTo("40");
    assertThat(rows).allSatisfy(r -> assertThat(r.sourcePointIds()).hasSize(2));
  }

  @Test
  void neverDividesByZeroOrNegativeDenominator() {
    var date = LocalDate.of(2026, 6, 30);
    assertThat(
            calculator.calculate(
                List.of(
                    point("a", "LIABILITIES", "10", "VND", date),
                    point("b", "OWNERS_EQUITY", "0", "VND", date))))
        .isEmpty();
    assertThat(
            calculator.calculate(
                List.of(
                    point("a", "LIABILITIES", "10", "VND", date),
                    point("b", "OWNERS_EQUITY", "-1", "VND", date))))
        .isEmpty();
  }

  @Test
  void refusesMixedUnitsAndPeriods() {
    var date = LocalDate.of(2026, 6, 30);
    assertThat(
            calculator.calculate(
                List.of(
                    point("a", "LIABILITIES", "10", "VND", date),
                    point("b", "OWNERS_EQUITY", "100", "USD", date))))
        .isEmpty();
    assertThat(
            calculator.calculate(
                List.of(
                    point("a", "LIABILITIES", "10", "VND", date),
                    point("b", "OWNERS_EQUITY", "100", "VND", date.minusMonths(3)))))
        .isEmpty();
  }

  @Test
  void refusesAmbiguousDuplicateInputs() {
    var date = LocalDate.of(2026, 6, 30);
    assertThat(
            calculator.calculate(
                List.of(
                    point("a", "LIABILITIES", "10", "VND", date),
                    point("x", "LIABILITIES", "12", "VND", date),
                    point("b", "OWNERS_EQUITY", "100", "VND", date))))
        .isEmpty();
  }
}
