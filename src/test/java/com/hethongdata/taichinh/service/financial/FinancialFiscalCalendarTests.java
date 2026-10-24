package com.hethongdata.taichinh.service.financial;
import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
class FinancialFiscalCalendarTests {
 @Test void fiscalQuarterThreeEndsInJune(){
  FinancialDisclosureDocumentStore.validateFiscalPeriod("HSG","bctc quy iii ndtc 2025-2026",LocalDate.of(2026,6,30),"QUARTER");
  assertThatThrownBy(()->FinancialDisclosureDocumentStore.validateFiscalPeriod("HSG","bctc quy iii ndtc 2025-2026",LocalDate.of(2026,9,30),"QUARTER")).isInstanceOf(IllegalArgumentException.class);
 }
 @Test void halfYearAndAnnualKeepDifferentBasis(){
  FinancialDisclosureDocumentStore.validateFiscalPeriod("HSG","bctc giua nien do ndtc 2025-2026",LocalDate.of(2026,3,31),"HALF_YEAR");
  FinancialDisclosureDocumentStore.validateFiscalPeriod("HSG","bctc ndtc 2024-2025",LocalDate.of(2025,9,30),"ANNUAL");
  assertThatThrownBy(()->FinancialDisclosureDocumentStore.validateFiscalPeriod("HSG","bctc giua nien do ndtc 2025-2026",LocalDate.of(2026,3,31),"QUARTER")).isInstanceOf(IllegalArgumentException.class);
 }
}
