package com.hethongdata.taichinh.service.financial;
import static org.assertj.core.api.Assertions.*;
import com.hethongdata.taichinh.dto.forecast.ForecastDtos.SourcePoint;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
class HistoricalFinancialMetricServiceTests {
    SourcePoint p(String code,String value) {return new SourcePoint(code,"INCOME_STATEMENT",code,
        new BigDecimal(value),"VND",LocalDate.of(2016,1,1),LocalDate.of(2016,3,31),"Q1","UNKNOWN",null);}
    @Test void keepsNegativeProfitAndCalculatesOnlyAvailableSameStatementRatios() {
        var rows=HistoricalFinancialMetricService.incomeRatios(List.of(p("NET_SALES","200"),p("NET_PROFIT_AFTER_TAX","-10")));
        assertThat(rows).hasSize(1);assertThat(rows.getFirst().code()).isEqualTo("NET_MARGIN");
        assertThat(rows.getFirst().value()).isEqualByComparingTo("-5");
    }
    @Test void skipsMissingZeroAndAmbiguousDenominators() {
        assertThat(HistoricalFinancialMetricService.incomeRatios(List.of(p("NET_PROFIT_AFTER_TAX","10")))).isEmpty();
        assertThat(HistoricalFinancialMetricService.incomeRatios(List.of(p("NET_SALES","0"),p("NET_PROFIT_AFTER_TAX","10")))).isEmpty();
        assertThat(HistoricalFinancialMetricService.incomeRatios(List.of(p("NET_SALES","200"),p("NET_SALES","210"),p("NET_PROFIT_AFTER_TAX","10")))).isEmpty();
    }
}
